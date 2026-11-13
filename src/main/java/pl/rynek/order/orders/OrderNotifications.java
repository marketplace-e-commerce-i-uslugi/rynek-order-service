package pl.rynek.order.orders;

import org.springframework.stereotype.Component;
import pl.rynek.order.clients.NotificationClient;

/**
 * Powiadomienia o zmianach zamówienia: sprzedawca dowiaduje się o opłaceniu, anulowaniu i zwrocie, kupujący o wysyłce,
 * realizacji, anulowaniu i decyzji w sprawie zwrotu. Klucz zdarzenia ({@code order-<id>-<zdarzenie>}) sprawia, że
 * ten sam krok zgłoszony dwiema ścieżkami (np. wysyłka przez sprzedawcę i odbiór przez przewoźnika) daje jedno powiadomienie.
 */
@Component
class OrderNotifications {

    private static final String BUYER_LINK = "/profil/zamowienia";
    private static final String SELLER_LINK = "/sprzedawca/zamowienia";

    private final NotificationClient notifications;

    OrderNotifications(NotificationClient notifications) {
        this.notifications = notifications;
    }

    void paid(Order o) {
        toSeller(o, "Opłacone, czeka na wysyłkę.", "paid");
    }

    void shipped(Order o) {
        toBuyer(o, "Paczka jest w drodze.", "shipped");
    }

    void completed(Order o) {
        toBuyer(o, "Zamówienie zostało zrealizowane. Możesz ocenić sprzedawcę.", "completed");
    }

    void cancelledByBuyer(Order o) {
        toSeller(o, "Kupujący anulował zamówienie. Nie wysyłaj paczki.", "cancelled");
    }

    void cancelledBySeller(Order o) {
        toBuyer(o, "Sprzedawca anulował zamówienie. Zapłacone pieniądze wracają do Ciebie.", "cancelled");
    }

    void expired(Order o) {
        toBuyer(o, "Zamówienie wygasło, bo nie zostało opłacone w terminie.", "cancelled");
    }

    void returnRequested(Order o) {
        toSeller(o, "Kupujący zgłosił zwrot. Przyjmij go albo odrzuć z uzasadnieniem.", "return-requested");
    }

    void returnRejected(Order o) {
        toBuyer(o, "Sprzedawca odrzucił zwrot. Uzasadnienie znajdziesz w zamówieniu.", "return-rejected");
    }

    /** Tylko gdy zwrot pieniędzy domyka zgłoszony zwrot; zwrot po anulowaniu ma własne powiadomienie. */
    void refunded(Order o) {
        if (o.getReturnRequest() != null && o.getReturnRequest().status() == ReturnStatus.REFUNDED) {
            toBuyer(o, "Zwrot przyjęty. Pieniądze wracają do Ciebie.", "return-accepted");
        }
    }

    private void toBuyer(Order o, String body, String event) {
        notifications.send(o.getBuyerId(), "orders", title(o), body, BUYER_LINK, key(o, event));
    }

    private void toSeller(Order o, String body, String event) {
        notifications.send(o.getSellerId(), "orders", title(o), body, SELLER_LINK, key(o, event + "-seller"));
    }

    private static String title(Order o) {
        return "Zamówienie #" + o.getNumber();
    }

    private static String key(Order o, String event) {
        return "order-" + o.getId() + "-" + event;
    }
}
