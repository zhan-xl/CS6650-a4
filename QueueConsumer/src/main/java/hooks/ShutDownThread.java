package hooks;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import java.util.List;
import pools.RMQChannelPool;

public class ShutDownThread extends Thread{

  private MongoClient mongoClient;
  private RMQChannelPool rmqChannelPool;
  private List<Thread> threadList;
  private Thread insertionThread;

  public ShutDownThread(ConsumerService consumerService) {
    this.mongoClient = consumerService.getMongoDBClient();
    this.rmqChannelPool = consumerService.getRmqChannelPool();
    this.threadList = consumerService.getThreadList();
    this.insertionThread = consumerService.getInsertionThread();
  }

  @Override
  public void run() {
    try {
      insertionThread.interrupt();
      mongoClient.close();
      rmqChannelPool.shutdown();
      for (Thread thread : threadList) {
        thread.interrupt();
      }
      System.out.println("Connections closed successfully.");
    } catch (MongoException e) {
      System.out.println("MongoDB client unable to close: " + e);
    } catch (Exception e) {
      System.out.println("Fail to close RabbitMq connection: " + e);
    }

  }
}
