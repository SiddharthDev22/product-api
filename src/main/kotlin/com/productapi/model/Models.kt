package com.productapi.model

import kotlinx.serialization.Serializable

@Serializable
data class Discount(
    val discountId: String,
    val percent: Double
)

@Serializable
data class Product(
    val id: String,
    val name: String,
    val basePrice: Double,
    val country: String,
    val discounts: List<Discount> = emptyList()
)

@Serializable
data class ProductResponse(
    val id: String,
    val name: String,
    val basePrice: Double,
    val country: String,
    val discounts: List<Discount>,
    val finalPrice: Double
)

@Serializable
data class ApplyDiscountRequest(
    val discountId: String,
    val percent: Double
)

enum class CountryVAT(val country: String, val vatPercent: Double) {
    SWEDEN("Sweden", 25.0),
    GERMANY("Germany", 19.0),
    FRANCE("France", 20.0);

    companion object {
        fun forCountry(country: String): Double =
            entries.firstOrNull { it.country.equals(country, ignoreCase = true) }?.vatPercent
                ?: throw IllegalArgumentException("Unsupported country: $country. Supported: ${entries.map { it.country }}")
    }
}

fun calculateFinalPrice(basePrice: Double, discounts: List<Discount>, vatPercent: Double): Double {
    val totalDiscount = discounts.sumOf { it.percent }.coerceAtMost(100.0)
    return basePrice * (1 - totalDiscount / 100.0) * (1 + vatPercent / 100.0)
}
