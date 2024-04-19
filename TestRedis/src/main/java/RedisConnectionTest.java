import redis.clients.jedis.Jedis;

public class RedisConnectionTest {
  public static void main(String[] args) {
    // Replace "localhost" and 6379 with your Redis server's host and port
    Jedis jedis = new Jedis("54.212.228.219", 6379);
    try {
      jedis.connect();
      System.out.println("Connected to Redis");
    } catch (Exception e) {
      System.out.println("Failed to connect to Redis: " + e.getMessage());
    } finally {
      jedis.close();
    }
  }
}

