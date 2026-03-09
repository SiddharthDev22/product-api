package com.productapi

import com.productapi.model.ApplyDiscountRequest
import com.productapi.model.ProductResponse
import com.productapi.repository.DatabaseFactory
import com.productapi.repository.DiscountsTable
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class ProductApiTest {

    @BeforeTest
    fun cleanDiscounts() {
        DatabaseFactory.init()
        transaction {
            DiscountsTable.deleteAll()
        }
    }

    private fun setupTestApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        //application { module() }
        block()  // ← removed startApplication(), uncommented block()
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
        val discountId = "test-discount-simple-${System.currentTimeMillis()}"  // ← unique per run

        val response = client.put("/products/prod-3/discount") {
            contentType(ContentType.Application.Json)
            setBody(ApplyDiscountRequest(discountId = discountId, percent = 10.0))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val product = response.body<ProductResponse>()
        // Germany VAT = 19%, discount = 10%
        // finalPrice = basePrice * 0.90 * 1.19
        val expected = product.basePrice * 0.90 * 1.19
        assertEquals(expected, product.finalPrice, 0.001)
        assertTrue(product.discounts.any { it.discountId == discountId })  // ← use variable
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

        results.forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status, "All concurrent requests should return 200")
        }

        val finalResponse = client.get("/products?country=Sweden")
        val products = finalResponse.body<List<ProductResponse>>()
        val targetProduct = products.first { it.id == productId }

        val discountOccurrences = targetProduct.discounts.count { it.discountId == discountId }
        assertEquals(1, discountOccurrences, "Discount '$discountId' must be stored exactly once despite $concurrency concurrent requests")

        println("✅ Concurrency test passed: $concurrency requests fired, discount applied exactly $discountOccurrences time(s)")
        println("   Final price: ${targetProduct.finalPrice} (base: ${targetProduct.basePrice})")
    }

    @Test
    fun `Final price formula is correct with multiple discounts`() = setupTestApp {
        val client = createTestClient()
        val productId = "prod-5"
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

        // totalDiscount = exactly 15% (DB cleaned before test), VAT = 20%
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