package com.estatevista.property.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the production image adapter only when explicitly enabled.
 *
 * <p>Disabled configuration leaves no Cloudinary bean, so the image service
 * continues to fail closed. Enabled but incomplete configuration fails fast at
 * bean creation without printing credential values.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudinaryProperties.class)
public class CloudinaryStorageConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "app.storage.cloudinary",
            name = "enabled",
            havingValue = "true")
    ImageStorage cloudinaryImageStorage(CloudinaryProperties properties) {
        return new CloudinaryImageStorage(properties);
    }
}
