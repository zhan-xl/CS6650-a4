package com.xiaolinzhan.skidataserver;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.rabbitmq.client.ConnectionFactory;
import com.xiaolinzhan.skidataserver.rmqpool.RMQChannelPool;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import org.bson.Document;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.clients.jedis.Jedis;

public class MyServletContextListener implements ServletContextListener {

  private RMQChannelPool rmqChannelPool;
  private MongoClient mongoClient;

  private ExecutorService executorService;

  private RedissonClient redissonClient;

  @Override
  public void contextInitialized(ServletContextEvent servletContextEvent) {

    // Create connection to RabbitMQ
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(System.getenv("RMQ_URL"));
    factory.setPort(5672);
    factory.setUsername(System.getenv("RMQ_USERNAME"));
    factory.setPassword(System.getenv("RMQ_PASSWORD"));
     factory.setVirtualHost("vh");
//    factory.setVirtualHost("/"); // Hyojin's vh

    int numOfChannel = Integer.parseInt(System.getenv("NUM_OF_CHANNEL"));

    rmqChannelPool = new RMQChannelPool(numOfChannel, factory);

    servletContextEvent.getServletContext().setAttribute("RMQChannelPool", rmqChannelPool);
    // Create connection to MongoDB
    String connectionString =
        "mongodb+srv://" + System.getenv("MONGODB_USERNAME") + ":" + System.getenv(
            "MONGODB_PASSWORD")
            + "@xiaolinwebdev.sq1refr.mongodb.net/?retryWrites=true&w=majority&appName=XiaolinWebDev";

    ServerApi serverApi = ServerApi.builder()
        .version(ServerApiVersion.V1)
        .build();

    MongoClientSettings settings = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(connectionString))
        .serverApi(serverApi)
        .build();

    try {
      mongoClient = MongoClients.create(settings);
      servletContextEvent.getServletContext().setAttribute("MongoClient", mongoClient);
    } catch (MongoException e) {
      System.out.println("Failed to create MongoDB client.");
    }

    // Create Executor Service for sending message to RabbitMQ
    executorService = new ThreadPoolExecutor(1, numOfChannel, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    servletContextEvent.getServletContext().setAttribute("ExecutorService", executorService);

    // Create redis connection
    Config redisConfig = new Config();
    redisConfig.useSingleServer()
            .setAddress("redis://" + System.getenv("RedisURL") + ":6379").setConnectionPoolSize(128).setConnectionMinimumIdleSize(4); // or we can do System.getenv("redis")
    redissonClient = Redisson.create(redisConfig);
    servletContextEvent.getServletContext().setAttribute("redis", redissonClient);

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

  @Override
  public void contextDestroyed(ServletContextEvent servletContextEvent) {
    rmqChannelPool.close();
    mongoClient.close();
    executorService.shutdown();
    redissonClient.shutdown();
    try {
      if (!executorService.awaitTermination(60L, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
