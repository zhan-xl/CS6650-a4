package com.xiaolinzhan.skidataserver.servlets;

import Runnables.SendMessageTask;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.xiaolinzhan.skidataserver.models.liftRide.LiftRideDocument;
import com.xiaolinzhan.skidataserver.models.liftRide.LiftRideBody;
import com.xiaolinzhan.skidataserver.models.liftRide.LiftRidePath;
import com.xiaolinzhan.skidataserver.rmqpool.RMQChannelPool;
import java.io.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.*;
import javax.servlet.annotation.*;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

@WebServlet(name = "skier servlet", value = "/skiers/*")
public class SkierServlet extends HttpServlet {

  private RMQChannelPool channelPool;

  private MongoClient mongoClient;
  private ExecutorService executorService;
  private RedissonClient redissonClient;

  //  private int lowerBound;
//
//  private int upperBound;
//
//  private int queueSize;


  public void init(ServletConfig config) throws ServletException {
    super.init(config);
//    lowerBound = Integer.parseInt(System.getenv("LOWER_BOUND"));
//    upperBound = Integer.parseInt(System.getenv("UPPER_BOUND"));
//    queueSize = 0;

    channelPool = (RMQChannelPool) getServletContext().getAttribute("RMQChannelPool");
    mongoClient = (MongoClient) getServletContext().getAttribute(
        "MongoClient");
    executorService = (ExecutorService) getServletContext().getAttribute("ExecutorService");
    redissonClient = (RedissonClient) getServletContext().getAttribute("redis");
  }

  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String urlPath = request.getPathInfo();
    String body = getBody(request);
    LiftRideBody liftRideBody = new Gson().fromJson(body, LiftRideBody.class);
    LiftRidePath liftRidePath = parseUrlPath(urlPath);

    if (liftRidePath == null || !dataValidation(liftRidePath, liftRideBody)) {
      System.out.println(liftRidePath);
      System.out.println(liftRideBody);
      sendBadRequestResponse(response);
      return;
    }

    LiftRideDocument liftRideDocument = new LiftRideDocument(liftRidePath, liftRideBody);

    executorService.execute(new SendMessageTask(channelPool, liftRideDocument));
    response.setStatus(HttpServletResponse.SC_CREATED);
    JsonObject responseJson = new JsonObject();
    responseJson.addProperty("Lift Ride Inserted", liftRideDocument.toString());
    response.getWriter().write(responseJson.toString());

  }

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("application/json");

    String urlPath = request.getPathInfo();
    if (urlPath == null || urlPath.isEmpty()) {
      sendBadRequestResponse(response);
      return;
    }
    String[] urlParas = urlPath.split("/");
    if (urlParas.length == 3) {
      String skierID = urlParas[1];
      String resortID = request.getParameter("resortID");
      String seasonID = request.getParameter("seasonID");

      String cacheKey = "skier: " + skierID + " resort: " + resortID + " season: " + seasonID;
      // Check if the data exists in the Redis cache
      RBucket<Map<String, Object>> bucket = redissonClient.getBucket(cacheKey);
      int totalVertical = 0;
      long currentTime = System.currentTimeMillis() / 1000;
      long timestamp = 0;
      long cacheExpiration = 0;

      if (bucket.isExists()) {
        Map<String, Object> cacheData = bucket.get();
        if (cacheData != null &&
            cacheData.containsKey("vertical") &&
            cacheData.containsKey("timestamp") &&
            cacheData.containsKey("cacheExpiration")
        ) {
          totalVertical = (int) cacheData.get("vertical");
          timestamp = (long) cacheData.get("timestamp");
          cacheExpiration = (long) cacheData.get("cacheExpiration");
        }
        if (currentTime - timestamp <= cacheExpiration) {
          JsonObject responseJson = new JsonObject();
          responseJson.addProperty("total vertical", totalVertical);
          response.getWriter().write(responseJson.toString());
          response.setStatus(HttpServletResponse.SC_OK);
        }
      } else {
        totalVertical = getVertical(bucket, skierID, resortID, seasonID);
        JsonObject responseJson = new JsonObject();
        responseJson.addProperty("total vertical", totalVertical);
        response.getWriter().write(responseJson.toString());
        response.setStatus(HttpServletResponse.SC_OK);
      }
    } else if (urlParas.length == 8) {
      LiftRidePath liftRidePath = parseUrlPath(urlPath);

      String cacheKey =
          "skier: " + liftRidePath.getSkierID() + " resort: " + liftRidePath.getResortID()
              + " season: " + liftRidePath.getSeasonID()
              + " day: " + liftRidePath.getDayID();
      // Check if the data exists in the Redis cache
      RBucket<Map<String, Object>> bucket = redissonClient.getBucket(cacheKey);
      if (bucket.isExists()) {
        Map<String, Object> cacheData = bucket.get();
        if (cacheData != null &&
            cacheData.containsKey("vertical") &&
            cacheData.containsKey("timestamp") &&
            cacheData.containsKey("cacheExpiration")
        ) {
          int vertical = (int) cacheData.get("vertical");
          long timestamp = (long) cacheData.get("timestamp");

          long currentTime = System.currentTimeMillis() / 1000;
          long cacheExpiration = (long) cacheData.get("cacheExpiration");

          if (currentTime - timestamp <= cacheExpiration) {
            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("total vertical", vertical);
            response.getWriter().write(responseJson.toString());
            response.setStatus(HttpServletResponse.SC_OK);
            return;
          }
        }
      }

      int totalVertical = getVertical(bucket, Integer.toString(liftRidePath.getSkierID()),
          Integer.toString(liftRidePath.getResortID()), liftRidePath.getSeasonID(),
          liftRidePath.getDayID());

      JsonObject responseJson = new JsonObject();
      responseJson.addProperty("total vertical", totalVertical);
      response.getWriter().write(responseJson.toString());
      response.setStatus(HttpServletResponse.SC_OK);
    } else {
      sendBadRequestResponse(response);
    }
  }

  private static void sendBadRequestResponse(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    JsonObject responseJson = new JsonObject();
    responseJson.addProperty("message", "invalid request.");
    response.getWriter().write(responseJson.toString());
  }

  private LiftRidePath parseUrlPath(String urlPath) {
    if (urlPath == null || urlPath.isEmpty()) {
      return null;
    }
    String[] urlParas = urlPath.split("/");
    if (urlParas.length < 8 || !urlParas[0].isEmpty() || !urlParas[2].equals("seasons")
        || !urlParas[4].equals("days") || !urlParas[6].equals("skiers")) {
      return null;
    }
    String resortID = urlParas[1];
    String seaSonsID = urlParas[3];
    String dayID = urlParas[5];
    String skierID = urlParas[7];

    return new LiftRidePath(Integer.parseInt(resortID), seaSonsID, dayID,
        Integer.parseInt(skierID));
  }

  public String getBody(HttpServletRequest request) throws IOException {
    BufferedReader reader = request.getReader();
    StringBuilder stringBuilder = new StringBuilder();

    String line;
    while ((line = reader.readLine()) != null) {
      stringBuilder.append(line);
      stringBuilder.append(System.lineSeparator());
    }

    return stringBuilder.toString();
  }

  private boolean dataValidation(LiftRidePath liftRidePath, LiftRideBody liftRideBody) {
    if (liftRidePath.getResortID() < 1
        || liftRidePath.getResortID() > 10) {
      return false;
    } else if (!liftRidePath.getSeasonID().equals("2024")) {
      return false;
    } else if (liftRidePath.getSkierID() < 1
        || liftRidePath.getSkierID() > 100000) {
      return false;
    } else if (liftRideBody.getTime() < 1 || liftRideBody.getTime() > 360) {
      return false;
    } else {
      return liftRideBody.getLiftID() >= 1 && liftRideBody.getLiftID() <= 40;
    }
  }

  private int getVertical(RBucket<Map<String, Object>> bucket, String skierID, String resortID,
      String... optionalArgs) {
    Bson projectionFields = Projections.fields(Projections.include("liftID"),
        Projections.excludeId());
    Bson filter;
    // added null check - /skiers/{skierID}/vertical api doesn't have resortID; caused 500 error
    if (resortID != null) {
      filter = Filters.and(Filters.eq("skierID", Integer.parseInt(skierID)),
          Filters.eq("resortID", Integer.parseInt(resortID)));
    } else {
      filter = Filters.and(Filters.eq("skierID", Integer.parseInt(skierID)));
    }

    if (optionalArgs.length > 0 && optionalArgs[0] != null) {
      filter = Filters.and(filter, Filters.eq("seasonID", optionalArgs[0]));
    }
    if (optionalArgs.length > 1 && optionalArgs[1] != null) {
      filter = Filters.and(filter, Filters.eq("dayID", optionalArgs[1]));
    }
    int totalVertical = 0;
    try (
        MongoCursor<Document> cursor = mongoClient.getDatabase("SkiDatabase")
            .getCollection("liftRides").find(filter).projection(projectionFields)
            .iterator()) {
      while (cursor.hasNext()) {
        totalVertical += cursor.next().getInteger("liftID") * 10;
      }
    }

    // Store the result in cache for future requests
    long currentTime = System.currentTimeMillis() / 1000;

    Map<String, Object> cachestore = new HashMap<>();
    cachestore.put("vertical", totalVertical);
    cachestore.put("timestamp", currentTime);
    Duration cacheDuration = Duration.ofDays(1);
    long absoluteExpiration = currentTime + cacheDuration.getSeconds();
    cachestore.put("cacheExpiration", absoluteExpiration);
    bucket.set(cachestore); // redisson serializes to JSON

    bucket.expire(cacheDuration);

    return totalVertical;
  }
}