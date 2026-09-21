package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM order_history ORDER BY timestamp DESC")
    fun getAllOrders(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM order_history WHERE isAccepted = :accepted ORDER BY timestamp DESC")
    fun getOrdersByStatus(accepted: Boolean): Flow<List<OrderEntity>>

    @Query("SELECT * FROM order_history WHERE pickupLocation LIKE '%' || :query || '%' OR dropLocation LIKE '%' || :query || '%' OR decisionReason LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchOrders(query: String): Flow<List<OrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long

    @Query("DELETE FROM order_history")
    suspend fun clearAll()

    @Query("DELETE FROM order_history WHERE id = :orderId")
    suspend fun deleteOrder(orderId: Long)

    @Query("SELECT COUNT(*) FROM order_history")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM order_history WHERE isAccepted = 1")
    suspend fun getAcceptedCount(): Int
}
