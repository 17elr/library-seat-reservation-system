package cn.edu.library.service;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class MetadataCache {

    public static final String PREFIX = "library:metadata:v1:";
    private static final Logger log = LoggerFactory.getLogger(
        MetadataCache.class
    );
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final LibraryMapper db;

    @Value("${library.cache-enabled:true}")
    boolean enabled;

    public MetadataCache(
        StringRedisTemplate redis,
        ObjectMapper json,
        LibraryMapper db
    ) {
        this.redis = redis;
        this.json = json;
        this.db = db;
    }

    private <T> List<T> list(
        String key,
        Class<T> type,
        Supplier<List<T>> loader
    ) {
        String value = null;
        if (enabled) try {
            value = redis.opsForValue().get(PREFIX + key);
        } catch (RuntimeException e) {
            log.warn("Redis metadata read failed; using MySQL: {}", key);
        }
        if (value != null) try {
            return json.readValue(
                value,
                json.getTypeFactory().constructCollectionType(List.class, type)
            );
        } catch (Exception e) {
            log.warn("Invalid metadata cache: {}", key);
        }
        List<T> data = loader.get();
        if (enabled) try {
            redis
                .opsForValue()
                .set(
                    PREFIX + key,
                    json.writeValueAsString(data),
                    Duration.ofSeconds(
                        120 + ThreadLocalRandom.current().nextInt(31)
                    )
                );
        } catch (Exception e) {
            log.warn("Redis metadata fill failed: {}", key);
        }
        return data;
    }

    public List<Room> rooms() {
        return list("rooms", Room.class, db::rooms);
    }

    public List<Seat> seats(long roomId) {
        return list("seats:" + roomId, Seat.class, () -> db.seats(roomId));
    }

    public Rule ruleDisplay(LocalDateTime now) {
        return list("rules", Rule.class, db::allRules)
            .stream()
            .filter(q -> !q.effectiveAt.isAfter(now))
            .max(
                Comparator.comparing((Rule q) -> q.effectiveAt).thenComparing(
                    q -> q.id
                )
            )
            .orElse(null);
    }

    public void invalidateAfterCommit(String... keys) {
        Runnable invalidate = () -> {
            if (enabled) try {
                redis.delete(
                    Arrays.stream(keys)
                        .map(k -> PREFIX + k)
                        .toList()
                );
            } catch (RuntimeException e) {
                log.warn(
                    "Redis invalidation failed; bounded TTL will expire keys {}",
                    Arrays.toString(keys)
                );
            }
        };
        if (
            TransactionSynchronizationManager.isSynchronizationActive()
        ) TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            }
        );
        else invalidate.run();
    }
}
