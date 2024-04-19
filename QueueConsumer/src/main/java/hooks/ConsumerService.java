package hooks;

import com.google.gson.Gson;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import models.LiftRide;
import org.bson.Document;
import org.bson.types.ObjectId;
import pools.RMQChannelPool;

public class ConsumerService {

  private final String QUEUE_NAME = "liftRides";
  private final ConnectionFactory factory;
  private RMQChannelPool rmqChannelPool;
  private MongoClient mongoClient;
  private List<Thread> threadList;
  private Thread insertionThread;
  private BlockingQueue<Document> documentQueue;

  public ConsumerService(String rabbitMqUrl, int port, String username, String password,
      String virtualHost) {
    factory = new ConnectionFactory();
    factory.setHost(rabbitMqUrl);
    factory.setPort(port);
    factory.setUsername(username);
    factory.setPassword(password);
    factory.setVirtualHost(virtualHost);
    // Start connection to RabbitMQ
    rmqChannelPool = new RMQChannelPool(Integer.parseInt(System.getenv("NUM_OF_CONSUMERS")),
        factory);
    System.out.println("Connected to rabbitmq server at: " + System.getenv("RMQ_URL"));
    // Start connection to MongoDB
    String connectionString =
        "mongodb+srv://" + System.getenv("MONGODB_USERNAME") + ":" + System.getenv(
            "MONGODB_PASSWORD")
            + "@xiaolinwebdev.sq1refr.mongodb.net/?retryWrites=true&w=majority&appName=XiaolinWebDev";

    ServerApi serverApi = ServerApi.builder()
        .version(ServerApiVersion.V1)
        .build();

    MongoClientSettings settings = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(connectionString))
        .applyToConnectionPoolSettings(builder -> builder.minSize(64).maxSize(128))
        .serverApi(serverApi)
        .build();

    try {
      mongoClient = MongoClients.create(settings);
    } catch (MongoException e) {
      System.out.println("Failed to create MongoDB client.");
    }

    documentQueue = new LinkedBlockingDeque<>();
  }

  public void startConsume() {
    insertionThread = new Thread(
        new InsertionRunnable(mongoClient.getDatabase("SkiDatabase").getCollection("liftRides"),
            documentQueue));
    insertionThread.start();

    Runnable consumer = () -> {
      try {
        Channel channel = rmqChannelPool.borrowObject();
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
          String message = new String(delivery.getBody(), "UTF-8");
          LiftRide liftRide = new Gson().fromJson(message, LiftRide.class);
          Document document = new Document()
              .append("_id", new ObjectId())
              .append("dayID", liftRide.getDayID())
              .append("skierID", liftRide.getSkierID())
              .append("time", liftRide.getTime())
              .append("liftID", liftRide.getLiftID())
              .append("resortID", liftRide.getResortID())
              .append("seasonID", liftRide.getSeasonID());
          documentQueue.add(document);
        };

        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> {
        });
        rmqChannelPool.returnObject(channel);

      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };
    threadList = new ArrayList<>();
    for (int i = 0; i < Integer.parseInt(System.getenv("NUM_OF_CONSUMERS")); i++) {
      Thread newThread = new Thread(consumer);
      threadList.add(newThread);
      newThread.start();
    }
  }

  public MongoClient getMongoDBClient() {
    return mongoClient;
  }

  public RMQChannelPool getRmqChannelPool() {
    return rmqChannelPool;
  }

  public List<Thread> getThreadList() {
    return threadList;
  }

  public Thread getInsertionThread() {
    return insertionThread;
  }
}
