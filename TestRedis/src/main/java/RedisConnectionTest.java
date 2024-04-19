import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.clients.jedis.Jedis;

public class RedisConnectionTest {
  public static void main(String[] args) {
    // Replace "localhost" and 6379 with your Redis server's host and port

    Config config = new Config();
    config.useSingleServer().setAddress("redis://35.94.199.25:6379");
    RedissonClient redisson = Redisson.create(config);
    RKeys keys = redisson.getKeys();

    keys.flushdb();

    redisson.shutdown();
  }
}

