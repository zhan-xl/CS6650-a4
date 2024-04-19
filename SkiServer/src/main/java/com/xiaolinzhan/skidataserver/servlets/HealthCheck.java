package com.xiaolinzhan.skidataserver.servlets;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(name = "health check servlet", value = "/health-check")

public class HealthCheck extends HttpServlet {

  public void doGet(HttpServletRequest request, HttpServletResponse response) {
    response.setStatus(HttpServletResponse.SC_OK);
  }
}
