package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing an evaluated ride offer order history record.
 */
@Entity(tableName = "order_history")
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val fare: Double,
    val pickupDistanceKm: Double? = null,
    val dropDistanceKm: Double? = null,
    val pickupLocation: String = "",
    val dropLocation: String = "",
    val isAccepted: Boolean,
    val decisionReason: String,
    val rawText: String = ""
)
