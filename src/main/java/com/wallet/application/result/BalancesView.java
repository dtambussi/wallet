package com.wallet.application.result;

import java.math.BigDecimal;
import java.util.Map;

public record BalancesView(Map<String, BigDecimal> amountsByCurrency) {
}
