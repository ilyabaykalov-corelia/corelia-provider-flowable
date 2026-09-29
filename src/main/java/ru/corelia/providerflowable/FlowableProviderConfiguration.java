package ru.corelia.providerflowable;

import java.util.EnumSet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import ru.corelia.provider.ProviderCapability;
import ru.corelia.provider.ProviderDescriptor;

/** Регистрирует Flowable adapter в runtime, выбранном конфигурацией Corelia. */
@AutoConfiguration
@ConditionalOnExpression("'${corelia.provider:platform-v}' == 'flowable' || '${corelia.provider.workflow:}' == 'flowable' || '${corelia.provider.tasks:}' == 'flowable'")
@ComponentScan(basePackages = "ru.corelia.providerflowable")
public class FlowableProviderConfiguration {
    @Bean
    ProviderDescriptor flowableProviderDescriptor() {
        return new ProviderDescriptor() {
            @Override public String id() { return "flowable"; }
            @Override public java.util.Set<ProviderCapability> capabilities() {
                return EnumSet.of(ProviderCapability.WORKFLOW, ProviderCapability.TASKS);
            }
        };
    }
}
