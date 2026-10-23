package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintMoney(String minor, String currency, int scale) {
    public PrintMoney {
        if (minor == null
                || !minor.matches("-?[0-9]{1,30}")
                || scale < 0
                || scale > 8
                || currency == null
                || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("money");
    }
}
