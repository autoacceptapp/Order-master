package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.LicenseManager

/**
 * Room entity representing an evaluated ride offer order history record.
 */
@Entity(
    tableName = "order_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["isAccepted"])
    ]
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long = LicenseManager.getCurrentServerTimeMs(),
    val fare: Double,
    val pickupDistanceKm: Double? = null,
    val dropDistanceKm: Double? = null,
    val pickupLocation: String = "",
    val dropLocation: String = "",
    val isAccepted: Boolean,
    val decisionReason: String
)
