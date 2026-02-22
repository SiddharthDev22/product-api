package com.productapi.service

import com.productapi.model.*
import com.productapi.repository.ProductRepository

class ProductService(private val repository: ProductRepository = ProductRepository()) {

    suspend fun getProductsByCountry(country: String): List<ProductResponse> {
        val vatPercent = CountryVAT.forCountry(country)
        return repository.getByCountry(country).map { product ->
            product.toResponse(vatPercent)
        }
    }

    suspend fun applyDiscount(productId: String, request: ApplyDiscountRequest): ProductResponse {
        require(request.percent > 0 && request.percent < 100) {
            "Discount percent must be between 0 (exclusive) and 100 (exclusive)"
        }

        val product = repository.applyDiscount(productId, request.discountId, request.percent)
        val vatPercent = CountryVAT.forCountry(product.country)
        return product.toResponse(vatPercent)
    }

    private fun Product.toResponse(vatPercent: Double) = ProductResponse(
        id = id,
        name = name,
        basePrice = basePrice,
        country = country,
        discounts = discounts,
        finalPrice = calculateFinalPrice(basePrice, discounts, vatPercent)
    )
}
