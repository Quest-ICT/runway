package mx.quest.runway.web;

import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.stereotype.Component;

/**
 * Wraps every {@link FilterContribution} bean in an ordered {@code FilterRegistrationBean}, so a plugin filter
 * is registered with the host exactly like a kernel one — at the order {@link PluginFilterPlacement#resolve}
 * computes from its declared {@link mx.quest.runway.spi.FilterBand}+offset (clamped to the band). Running as a
 * {@link BeanFactoryPostProcessor}, it registers each as a singleton before the servlet context starts, so the
 * resulting filters <b>interleave with the kernel's own</b> by order — the band guarantee made real.
 *
 * <p>Trade-off (accepted): resolving the contributions here instantiates those (lightweight) filter beans early
 * — a servlet filter is not an AOP/transactional target, so that is fine; it is the price of computing each
 * filter's concrete order from its band before the chain is assembled. The {@code PluginManager} logs the
 * resolved order so drift is visible.
 */
@Component
public class PluginFilterRegistrar implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PluginFilterRegistrar.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getBeanNamesForType(FilterContribution.class, true, false)) {
            FilterContribution contribution = beanFactory.getBean(name, FilterContribution.class);
            int order = PluginFilterPlacement.resolve(contribution.band(), contribution.offset());
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(contribution.filter());
            registration.setOrder(order);
            registration.addUrlPatterns("/*");
            registration.setName(contribution.name());
            beanFactory.registerSingleton("pluginFilterRegistration-" + name, registration);
            log.info("registered plugin filter '{}' ({}+{}) at order {}",
                    contribution.name(), contribution.band(), contribution.offset(), order);
        }
    }
}
