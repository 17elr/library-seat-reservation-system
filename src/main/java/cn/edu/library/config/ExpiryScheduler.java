package cn.edu.library.config;

import cn.edu.library.service.LibraryService;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(
        ExpiryScheduler.class
    );
    private final LibraryService service;

    public ExpiryScheduler(LibraryService service) {
        this.service = service;
    }

    @Scheduled(
        fixedDelayString = "${library.sweep-delay:30000}",
        initialDelayString = "${library.sweep-delay:30000}"
    )
    public void sweep() {
        try {
            service.sweep();
        } catch (RuntimeException e) {
            log.error("预约超时处理失败，下次扫描重试", e);
        }
    }
}
