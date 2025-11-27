package org.cardanofoundation.cip113.config;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class YaciConfiguration {

    @Bean
    public QuickTxBuilder quickTxBuilder(BFBackendService bfBackendService) {
        return new QuickTxBuilder(bfBackendService);
    }


}
