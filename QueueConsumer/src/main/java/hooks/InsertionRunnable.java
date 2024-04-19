package hooks;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.InsertManyResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import org.bson.Document;

public class InsertionRunnable implements Runnable {

  private volatile boolean running = true;
  private MongoCollection<Document> collection;
  private BlockingQueue<Document> documentQueue;

  public InsertionRunnable(MongoCollection<Document> collection,
      BlockingQueue<Document> documentQueue) {
    this.collection = collection;
    this.documentQueue = documentQueue;
  }

  public void stop() {
    running = false;
  }

  @Override
  public void run() {
    while (running) {
      List<Document> documents = new ArrayList<>();
      if (!documentQueue.isEmpty()) {
        documentQueue.drainTo(documents);
        InsertManyResult result = collection.insertMany(documents);
      } else {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          System.out.println("Insertion thread closed successfully.");
        }
      }
      System.out.println("Total number of inserted documents: " + documents.size());
    }
  }
}
