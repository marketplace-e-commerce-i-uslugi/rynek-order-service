package pl.rynek.order.api.dto;

/** Kwota w groszach: {@code {"amount": 38900, "currency": "PLN"}} = 389,00 zł. */
public record MoneyDto(long amount, String currency) {

    public static MoneyDto pln(long amount) {
        return new MoneyDto(amount, "PLN");
    }
}
