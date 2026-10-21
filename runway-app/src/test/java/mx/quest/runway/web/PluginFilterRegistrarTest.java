package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import mx.quest.runway.spi.FilterBand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/** A plugin's FilterContribution is wrapped into an ordered FilterRegistrationBean at its band's resolved order. */
@SpringBootTest
class PluginFilterRegistrarTest {

    static final class MarkerFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(request, response);
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        FilterContribution appFilterContribution() {
            return new FilterContribution() {
                @Override
                public Filter filter() {
                    return new MarkerFilter();
                }

                @Override
                public FilterBand band() {
                    return FilterBand.APPLICATION;
                }

                @Override
                public String name() {
                    return "test-app-filter";
                }
            };
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theContributionIsRegisteredAtItsBandResolvedOrder() {
        int expected = PluginFilterPlacement.resolve(FilterBand.APPLICATION, 0); // inner of CORE_SECURITY
        boolean registered = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .anyMatch(reg -> reg.getFilter() instanceof MarkerFilter && reg.getOrder() == expected);
        assertTrue(registered, "the FilterContribution must be wrapped at order " + expected
                + " (resolved from APPLICATION), inner of security");
    }
}
