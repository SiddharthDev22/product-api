package com.productapi

import com.productapi.model.ApplyDiscountRequest
import com.productapi.model.ProductResponse
import com.productapi.repository.DatabaseFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class ProductApiTest {

    private fun setupTestApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            // Init DB with test config (assumes local postgres or CI environment)
            DatabaseFactory.init()
            module()
        }
        block()
    }

    @Test
    fun `GET products returns products with final price for valid country`() = setupTestApp {
        val client = createTestClient()

        val response = client.get("/products?country=Sweden")
        assertEquals(HttpStatusCode.OK, response.status)

        val products = response.body<List<ProductResponse>>()
        assertTrue(products.isNotEmpty())
        products.forEach { product ->
            assertEquals("Sweden", product.country)
            assertTrue(product.finalPrice > 0)
            // For Sweden: finalPrice = basePrice * 1.25 (no discounts initially)
            val expectedFinalPrice = product.basePrice * 1.25
            assertEquals(expectedFinalPrice, product.finalPrice, 0.001)
        }
    }

    @Test
    fun `GET products returns 400 for missing country parameter`() = setupTestApp {
        val client = createTestClient()
        val response = client.get("/products")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET products returns 400 for unsupported country`() = setupTestApp {
        val client = createTestClient()
        val response = client.get("/products?country=Narnia")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT discount applies discount and recalculates final price`() = setupTestApp {
        val client = createTestClient()

        val response = client.put("/products/prod-3/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = "test-discount-simple", percent = 10.0))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val product = response.body<ProductResponse>()
        // Germany VAT = 19%, discount = 10%
        // finalPrice = basePrice * 0.90 * 1.19
        val expected = product.basePrice * 0.90 * 1.19
        assertEquals(expected, product.finalPrice, 0.001)
        assertTrue(product.discounts.any { it.discountId == "test-discount-simple" })
    }

    @Test
    fun `PUT discount returns 404 for unknown product`() = setupTestApp {
        val client = createTestClient()
        val response = client.put("/products/nonexistent-id/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = "d1", percent = 5.0))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT discount is idempotent - applying same discount twice has no side effects`() = setupTestApp {
        val client = createTestClient()
        val discountId = "idempotent-test-${System.currentTimeMillis()}"

        val first = client.put("/products/prod-1/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = discountId, percent = 15.0))
        }
        val second = client.put("/products/prod-1/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = discountId, percent = 15.0))
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)

        val productFirst = first.body<ProductResponse>()
        val productSecond = second.body<ProductResponse>()

        // Discount should only appear once
        val discountCount = productSecond.discounts.count { it.discountId == discountId }
        assertEquals(1, discountCount, "Discount should only be stored once (idempotent)")
        assertEquals(productFirst.finalPrice, productSecond.finalPrice, 0.001)
    }

    @Test
    fun `PUT discount is concurrency-safe - concurrent identical requests apply discount only once`() = setupTestApp {
        val client = createTestClient()
        val discountId = "concurrent-test-${System.currentTimeMillis()}"
        val productId = "prod-2"
        val concurrency = 20

        // Fire 20 concurrent requests with the same discountId
        val results = coroutineScope {
            (1..concurrency).map {
                async(Dispatchers.IO) {
                    client.put("/products/$productId/discount") {
                        contentType(ContentType.Application.Json)
                        setBody(ApplyDiscountRequest(discountId = discountId, percent = 20.0))
                    }
                }
            }.awaitAll()
        }

        // All requests should succeed (200 OK)
        results.forEach { response ->
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "All concurrent requests should return 200"
            )
        }

        // The discount should appear exactly once
        val finalResponse = client.get("/products?country=Sweden")
        val products = finalResponse.body<List<ProductResponse>>()
        val targetProduct = products.first { it.id == productId }

        val discountOccurrences = targetProduct.discounts.count { it.discountId == discountId }
        assertEquals(
            1,
            discountOccurrences,
            "Discount '$discountId' must be stored exactly once despite $concurrency concurrent requests"
        )

        println("✅ Concurrency test passed: $concurrency requests fired, discount applied exactly $discountOccurrences time(s)")
        println("   Final price: ${targetProduct.finalPrice} (base: ${targetProduct.basePrice})")
    }

    @Test
    fun `Final price formula is correct with multiple discounts`() = setupTestApp {
        val client = createTestClient()
        val productId = "prod-5" // France, basePrice = 3.75
        val ts = System.currentTimeMillis()

        client.put("/products/$productId/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = "formula-test-d1-$ts", percent = 10.0))
        }
        client.put("/products/$productId/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = "formula-test-d2-$ts", percent = 5.0))
        }

        val response = client.get("/products?country=France")
        val products = response.body<List<ProductResponse>>()
        val product = products.first { it.id == productId }

        // totalDiscount = 15%, VAT = 20%
        // finalPrice = basePrice * 0.85 * 1.20
        val totalDiscount = product.discounts.sumOf { it.percent }
        val expectedFinalPrice = product.basePrice * (1 - totalDiscount / 100.0) * 1.20
        assertEquals(expectedFinalPrice, product.finalPrice, 0.001)
    }

    private fun ApplicationTestBuilder.createTestClient(): HttpClient {
        return createClient {
            install(ContentNegotiation) {
                json(Json { isLenient = true })
            }
        }
    }
}
