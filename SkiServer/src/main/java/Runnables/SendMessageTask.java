package Runnables;

import com.google.gson.Gson;
import com.rabbitmq.client.Channel;
import com.xiaolinzhan.skidataserver.models.liftRide.LiftRideDocument;
import com.xiaolinzhan.skidataserver.rmqpool.RMQChannelPool;
import java.io.IOException;

public class SendMessageTask implements Runnable{
  private final RMQChannelPool pool;
  private final LiftRideDocument liftRideDocument;

  public SendMessageTask(RMQChannelPool pool, LiftRideDocument liftRideDocument) {
    this.pool = pool;
    this.liftRideDocument = liftRideDocument;
  }

  @Override
  public void run() {
    try {
      Channel channel = pool.borrowObject();
      channel.basicPublish("", "liftRides", null,
          new Gson().toJson(liftRideDocument, LiftRideDocument.class).getBytes());
      pool.returnObject(channel);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

}
