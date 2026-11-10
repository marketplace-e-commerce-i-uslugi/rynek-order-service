package pl.rynek.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class OrderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
