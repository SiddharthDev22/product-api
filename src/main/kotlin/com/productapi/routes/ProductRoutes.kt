package com.productapi.routes

import com.productapi.model.ApplyDiscountRequest
import com.productapi.service.ProductService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.productRoutes(service: ProductService = ProductService()) {
    routing {
        route("/products") {

            // GET /products?country={country}
            get {
                val country = call.request.queryParameters["country"]
                    ?: throw IllegalArgumentException("Query parameter 'country' is required")

                val products = service.getProductsByCountry(country)
                call.respond(HttpStatusCode.OK, products)
            }

            // PUT /products/{id}/discount
            put("/{id}/discount") {
                val productId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Path parameter 'id' is required")

                val request = call.receive<ApplyDiscountRequest>()
                val product = service.applyDiscount(productId, request)
                call.respond(HttpStatusCode.OK, product)
            }
        }
    }
}
