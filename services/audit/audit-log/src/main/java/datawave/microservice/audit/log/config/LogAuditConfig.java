package datawave.microservice.audit.log.config;

import datawave.microservice.audit.common.AuditMessageHandler;
import datawave.microservice.audit.common.Auditor;
import datawave.microservice.audit.log.LogAuditor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LogAuditProperties.class)
@ConditionalOnProperty(name = "audit.log.enabled", havingValue = "true")
public class LogAuditConfig {
    
    @Bean
    Queue logAuditQueue(LogAuditProperties logAuditProperties) {
        return new Queue(logAuditProperties.getQueueName(), logAuditProperties.isDurable());
    }
    
    @Bean
    Binding logAuditBinding(Queue logAuditQueue, FanoutExchange auditExchange) {
        return BindingBuilder.bind(logAuditQueue).to(auditExchange);
    }
    
    @Bean
    SimpleMessageListenerContainer logAuditContainer(LogAuditProperties logAuditProperties, ConnectionFactory connectionFactory, MessageListenerAdapter logAuditListenerAdapter) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setQueueNames(logAuditProperties.getQueueName());
        container.setMessageListener(logAuditListenerAdapter);
        return container;
    }
    
    @Bean
    Auditor logAuditor() {
        return new LogAuditor();
    }
    
    @Bean
    AuditMessageHandler logAuditMessageHandler(Auditor logAuditor) {
        return new AuditMessageHandler(logAuditor);
    }
    
    @Bean
    MessageListenerAdapter logAuditListenerAdapter(AuditMessageHandler logAuditMessageHandler) {
        return new MessageListenerAdapter(logAuditMessageHandler, logAuditMessageHandler.LISTENER_METHOD);
    }
}