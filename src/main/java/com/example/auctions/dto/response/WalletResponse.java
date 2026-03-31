package com.example.auctions.dto.response;

import java.math.BigDecimal;

public record WalletResponse(
        BigDecimal balance,
        PageResponse<WalletTransactionResponse> transactions
) {
}
