package com.example.auctions.model;

public enum TransactionStatus {
    PENDING, // Đang chờ xử lý
    COMPLETED, // Đã hoàn thành
    CANCELLED, // Đã hủy
    FAILED // Thanh toán thất bại
}