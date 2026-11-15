package in.codefarm.stress_testing_microservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class StressTestingMicroserviceApplicationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reserveSyncConfirmsOrderAndDecrementsStock() throws Exception {
        int before = available(42L);

        mvc.perform(post("/orders/reserve-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":12345,\"productId\":42,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        assertThat(available(42L)).isEqualTo(before - 2);
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        mvc.perform(post("/orders/reserve-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":999999999,\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void insufficientStockIs409AndNothingIsReserved() throws Exception {
        jdbc.update("UPDATE inventory SET available = 1 WHERE product_id = 7");

        mvc.perform(post("/orders/reserve-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"productId\":7,\"quantity\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_STOCK"));

        assertThat(available(7L)).isEqualTo(1);
    }

    @Test
    void invalidPayloadIs400() throws Exception {
        mvc.perform(post("/orders/reserve-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"productId\":7,\"quantity\":50}"))
                .andExpect(status().isBadRequest());
    }

    private int available(long productId) {
        return jdbc.queryForObject("SELECT available FROM inventory WHERE product_id = ?", Integer.class, productId);
    }
}
