package pl.rynek.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.rynek.order.TestcontainersConfig;
import pl.rynek.order.clients.CartClient;
import pl.rynek.order.clients.CatalogClient;
import pl.rynek.order.clients.CatalogClient.Money;
import pl.rynek.order.clients.CatalogClient.OfferInfo;
import pl.rynek.order.clients.ChannelClient;
import pl.rynek.order.clients.IdentityClient;
import pl.rynek.order.clients.IdentityClient.Address;
import pl.rynek.order.clients.ShippingClient;
import pl.rynek.order.clients.ShippingClient.Method;
import pl.rynek.order.orders.OrderService;

/**
 * Wygasanie nieopłaconych zamówień. Osobny kontekst z limitem 0 s i wyłączonym harmonogramem: test wywołuje
 * {@link OrderService#expireUnpaid()} sam, a inne testy nie tracą swoich zamówień.
 */
@SpringBootTest(properties = {"rynek.orders.payment-timeout=0s", "rynek.orders.expiry-check=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OrderExpiryTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrderService orders;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    CartClient cart;

    @MockitoBean
    IdentityClient identity;

    @MockitoBean
    ShippingClient shipping;

    @MockitoBean
    ChannelClient channels;

    final UUID buyer = UUID.randomUUID();
    final UUID seller = UUID.randomUUID();
    final UUID offer = UUID.randomUUID();
    final UUID address = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256")
                .subject(call.<String>getArgument(0).substring(5)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
        given(catalog.offers(any())).willReturn(Map.of(offer,
                new OfferInfo(offer, "PRODUCT", new Money(5000, "PLN"), 10, true, "Kubek", UUID.randomUUID(), seller, null, 1)));
        given(catalog.release(any(), any())).willReturn(true);
        given(identity.address(any(), any())).willReturn(Optional.of(new Address(address, null, "Anna Nowak", "+48600100200", "ul. Długa 5", "00-950", "Warszawa")));
        given(shipping.method("courier")).willReturn(Optional.of(new Method("courier", "Kurier", new Money(1599, "PLN"), "1–2 dni", false)));
        given(channels.batch(any())).willReturn(Map.of());
    }

    @Test
    void unpaidOrdersExpireAndReleaseStockButPaidOnesStay() throws Exception {
        String unpaid = buy("k1");
        String paid = buy("k2");
        mvc.perform(post("/internal/orders/" + paid + "/mark-paid")).andExpect(status().isOk());

        orders.expireUnpaid();

        mvc.perform(get("/api/v1/orders/" + unpaid).header(HttpHeaders.AUTHORIZATION, "Bearer user-" + buyer))
                .andExpect(jsonPath("$.status").value("Anulowane"));
        mvc.perform(get("/api/v1/orders/" + paid).header(HttpHeaders.AUTHORIZATION, "Bearer user-" + buyer))
                .andExpect(jsonPath("$.status").value("Do wysłania"));
        verify(catalog).release(eq(offer), any());
        // Wygasłego zamówienia nie da się już opłacić.
        mvc.perform(post("/internal/orders/" + unpaid + "/mark-paid")).andExpect(status().isConflict());
    }

    private String buy(String key) throws Exception {
        String body = "{\"source\":\"BUY_NOW\",\"offerId\":\"" + offer + "\",\"quantity\":1,\"addressId\":\"" + address
                + "\",\"shippingMethodId\":\"courier\"}";
        return JsonPath.read(mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + buyer)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$[0].id");
    }
}
