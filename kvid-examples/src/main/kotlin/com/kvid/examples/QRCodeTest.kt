package com.kvid.examples

import com.kvid.core.JvmQRCodeGenerator

fun main() {
    val generator = JvmQRCodeGenerator()

    println("Testing QR Code generation with various data sizes...")

    // Test 1: Small data (should work)
    try {
        val smallData = "Hello World"
        val qr1 = generator.generateQRCode(smallData)
        println("✓ Small data (${smallData.length} chars): SUCCESS")
    } catch (e: Exception) {
        println("✗ Small data: FAILED - ${e.message}")
    }

    // Test 2: Medium data (should work with version 30)
    try {
        val mediumData = "The quick brown fox jumps over the lazy dog. ".repeat(10)
        val qr2 = generator.generateQRCode(mediumData)
        println("✓ Medium data (${mediumData.length} chars): SUCCESS")
    } catch (e: Exception) {
        println("✗ Medium data: FAILED - ${e.message}")
    }

    // Test 3: Large data with version 40 (should work)
    try {
        val largeData = "The quick brown fox jumps over the lazy dog. This is a test. ".repeat(10)
        val qr3 = generator.generateQRCode(largeData, version = 40, errorCorrection = "L")
        println("✓ Large data with version 40 (${largeData.length} chars): SUCCESS")
    } catch (e: Exception) {
        println("✗ Large data with version 40: FAILED - ${e.message}")
    }

    // Test 4: Too large data (should fail with helpful message)
    try {
        val tooLargeData = "The quick brown fox jumps over the lazy dog. This is a test. ".repeat(50)
        val qr4 = generator.generateQRCode(tooLargeData, version = 30, errorCorrection = "M")
        println("✗ Too large data: Should have failed but didn't")
    } catch (e: Exception) {
        if (e.message?.contains("Data too big") == true) {
            println("✓ Too large data with version 30: Correctly rejected with helpful message")
            println("  Message: ${e.message}")
        } else {
            println("✗ Too large data: Failed with unexpected error - ${e.message}")
        }
    }

    println("\nAll tests completed!")
}