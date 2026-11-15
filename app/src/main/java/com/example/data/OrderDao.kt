package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM order_history ORDER BY timestamp DESC LIMIT 500")
    fun getAllOrders(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM order_history WHERE isAccepted = :accepted ORDER BY timestamp DESC LIMIT 500")
    fun getOrdersByStatus(accepted: Boolean): Flow<List<OrderEntity>>

    @Query("SELECT * FROM order_history WHERE pickupLocation LIKE '%' || :query || '%' OR dropLocation LIKE '%' || :query || '%' OR decisionReason LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 500")
    fun searchOrders(query: String): Flow<List<OrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long

    @Query("DELETE FROM order_history WHERE id NOT IN (SELECT id FROM order_history ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneOldOrders(keepCount: Int = 500)

    @Query("DELETE FROM order_history WHERE timestamp < :cutoffTimestampMs")
    suspend fun pruneOrdersOlderThan(cutoffTimestampMs: Long): Int

    @Query("DELETE FROM order_history")
    suspend fun clearAll()

    @Query("DELETE FROM order_history WHERE id = :orderId")
    suspend fun deleteOrder(orderId: Long)

    @Query("SELECT COUNT(*) FROM order_history")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM order_history WHERE isAccepted = 1")
    suspend fun getAcceptedCount(): Int
}
