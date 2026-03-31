package com.example.auctions.model;

public enum WalletTransactionType {
    TOP_UP, // Nạp tiền vào ví
    PAYMENT_OUT, // Trừ tiền khi thanh toán (buyer)
    SALE_IN // Nhận tiền khi bán được hàng (seller)
}
