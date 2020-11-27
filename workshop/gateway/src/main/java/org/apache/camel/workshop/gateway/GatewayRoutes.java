package org.apache.camel.workshop.gateway;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.caffeine.CaffeineConstants;
import org.apache.camel.http.common.HttpMethods;
import org.apache.camel.impl.saga.InMemorySagaService;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
@Component
public class GatewayRoutes extends RouteBuilder {



    @Override
    public void configure() throws Exception {

        restConfiguration()
                .component("servlet")
                .enableCORS(true);

        // routes here

        rest().get("/payments")
            .route()
            .serviceCall("credit/api/payments");

        rest().get("/purchases")
            .route()
            .serviceCall("inventory/api/purchases");

        rest().get("/items")
            .route()
            .serviceCall("inventory/api/items");

        rest().post("/orders")
                .type(Order.class)
                .route()
                .saga()
                    .compensation("direct:cancelOrder")
                    .option("order", simple("${body}"))
                        .unmarshal().json(JsonLibrary.Jackson, Order.class)
                        .to("bean-validator:validateOrder")
                        .multicast().parallelProcessing()
                            .to("direct:payOrder")
                            .to("direct:purchaseOrderItems")
                        .end()
                        .marshal().json(JsonLibrary.Jackson)
                .end();

        from("direct:cancelOrder")
                .setBody(header("order")).convertBodyTo(String.class)
                .unmarshal().json(JsonLibrary.Jackson, Order.class)
                .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.DELETE))
                .multicast().parallelProcessing()
                    .serviceCall("credit/api/payments/${body.reference}")
                    .serviceCall("inventory/api/purchases/${body.reference}");

        // Sub-route for credit
        from("direct:payOrder")
                .setBody().body(Order.class, this::createPayment)
                .marshal().json(JsonLibrary.Jackson)
                .serviceCall("credit/api/payments");

        // Sub-route for inventory
        from("direct:purchaseOrderItems")
                .setHeader("reference", simple("${body.reference}"))
                .split().simple("${body.items}").parallelProcessing()
                .serviceCall("inventory/api/purchases/${header.reference}/items/${body.id}?amount=${body.amount}");

    }

    /*
     * Utility methods
     */

    private Payment createPayment(Order order) {
        Payment payment = new Payment();
        payment.setUser(order.getUser());
        payment.setReference(order.getReference());
        payment.setAmount(order.getPrice());
        return payment;
    }

    // Needed later when we add recommendations
    private Catalog recommend(Catalog catalog, List<String> recomm) {
        if (recomm != null && catalog != null && catalog.getItems() != null) {
            for (String recommItem : recomm) {
                Item item = catalog.getItems().get(recommItem);
                if (item != null) {
                    item.setRecommended(true);
                }
            }
        }
        return catalog;
    }
}
