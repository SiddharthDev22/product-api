package com.productapi

// src/test/kotlin/com/productapi/BaseTest.kt
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
abstract class BaseTest {
    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:15").apply {
            withDatabaseName("productdb")
            withUsername("test")
            withPassword("test")
            start()
        }
    }
}