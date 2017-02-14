package com.slaykovsky.pgrapher;

import io.vertx.core.*;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.PostgreSQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class Grapher extends AbstractVerticle {
    final private String INIT_DB = "CREATE TABLE IF NOT EXISTS tests " +
            "(id SERIAL, hostname TEXT, test TEXT, threads INT, run INT, result REAL)";
    final private String SELECT_HOSTNAMES = "SELECT DISTINCT ON (hostname) hostname FROM tests";
    final private String SELECT_TESTS = "SELECT hostname, test, threads, avg(result) " +
            "AS average_result FROM tests GROUP BY hostname, test, threads ORDER BY test";
    final private String SELECT_ALL_HOSTNAME_TESTS = "SELECT test, threads, avg(result) AS average_result " +
            "FROM tests WHERE hostname = ? GROUP BY test, threads ORDER BY test";
    final private String DELETE_TEST = "DELETE FROM tests WHERE id = ?";
    final private String INSERT_TEST = "INSERT INTO tests (hostname, test, threads, run, result) " +
            "VALUES (?, ?, ?, ?, ?)";

    final JsonObject postgreSQLClientConfig = new JsonObject()
            .put("host", "database")
            .put("username", "pgrapher")
            .put("password", "password")
            .put("database", "pgrapher");

    private AsyncSQLClient client;

    private void initialData(Handler<Void> done) {
        client.getConnection(res -> {
            if (res.failed()) {
                throw new RuntimeException(res.cause());
            }

            final SQLConnection conn = res.result();

            conn.execute(INIT_DB, query -> {
                if (query.failed()) {
                    throw new RuntimeException(query.cause());
                }

                done.handle(null);
            });
        });
    }

    @Override
    public void start(Future<Void> future) {
        client = PostgreSQLClient.createShared(vertx, postgreSQLClientConfig);
        initialData(ready -> {
            Router router = Router.router(vertx);

//            router.route("/static/*").handler(StaticHandler.create());
            router.route().handler(BodyHandler.create());
            router.get("/static/*").handler(StaticHandler.create());

            router.route("/api/*").handler(ctx -> client.getConnection(res -> {
                if (res.failed()) {
                    ctx.fail(res.cause());
                } else {
                    SQLConnection conn = res.result();

                    ctx.put("conn", conn);

                    ctx.addHeadersEndHandler(done -> conn.close(close -> {
                    }));

                    ctx.next();
                }
            })).failureHandler(ctx -> {
                SQLConnection conn = ctx.get("conn");
                if (conn != null) {
                    conn.close(close -> {
                    });
                }
            });

            router.get("/api/machines").handler(ctx -> {
                HttpServerResponse response = ctx.response();
                SQLConnection conn = ctx.get("conn");

                conn.query(SELECT_HOSTNAMES, query -> {
                    if (query.failed()) {
                        response.setStatusCode(500).end();
                    } else {
                        JsonArray array = new JsonArray();
                        query.result().getRows().forEach(array::add);
                        response.putHeader("content-type", "application/json")
                                .end(array.encodePrettily());
                    }
                });
            });


            router.get("/api/tests").handler(ctx -> {
                HttpServerResponse response = ctx.response();
                SQLConnection conn = ctx.get("conn");

                conn.query(SELECT_TESTS, query -> {
                    if (query.failed()) {
                        response.setStatusCode(500).end();
                    } else {
                        JsonArray array = new JsonArray();
                        query.result().getRows().forEach(array::add);
                        response.putHeader("content-type", "application/json; charset=utf-8")
                                .end(array.encodePrettily());
                    }
                });
            });

            router.get("/api/tests/:hostname").handler(ctx -> {
                String hostname = ctx.request().getParam("hostname");
                HttpServerResponse response = ctx.response();

                if (hostname == null) {
                    response.setStatusCode(400).end();
                } else {

                    SQLConnection conn = ctx.get("conn");

                    conn.queryWithParams(SELECT_ALL_HOSTNAME_TESTS, new JsonArray().add(hostname), query -> {
                        if (query.failed()) {
                            response.setStatusCode(500).end();
                        }

                        if (query.result().getNumRows() == 0) {
                            response.setStatusCode(404).end();
                        } else {
                            JsonArray array = new JsonArray();
                            query.result().getRows().forEach(array::add);
                            response.putHeader("content-type", "application/json")
                                    .end(array.encodePrettily());
                        }
                    });
                }
            });

            router.delete("/api/tests/:testId").handler(ctx -> {
                HttpServerResponse response = ctx.response();
                String testId = ctx.request().getParam("testId");

                if (testId == null) {
                    ctx.response().setStatusCode(400).end();
                } else {
                    SQLConnection conn = ctx.get("conn");
                    conn.queryWithParams(DELETE_TEST, new JsonArray().add(Integer.parseInt(testId)), query -> {
                        if (query.failed()) {
                            response.setStatusCode(500).end();
                        } else {
                            response.setStatusCode(204).end();
                        }
                    });
                }
            });

            router.post("/api/tests").handler(ctx -> {
                HttpServerResponse response = ctx.response();
                SQLConnection conn = ctx.get("conn");

                JsonObject body = ctx.getBodyAsJson();
                if (body == null) {
                    response.setStatusCode(400).end();
                } else {
                    if (body.getString("hostname") == null
                            || body.getString("test") == null
                            || body.getString("threads") == null
                            || body.getString("run") == null
                            || body.getString("result") == null) {
                        response.setStatusCode(400).end();
                    } else {
                        conn.queryWithParams(INSERT_TEST, new JsonArray()
                                .add(body.getString("hostname"))
                                .add(body.getString("test"))
                                .add(Integer.parseInt(body.getString("threads")))
                                .add(Integer.parseInt(body.getString("run")))
                                .add(Double.parseDouble(body.getString("result"))), query -> {
                            if (query.failed()) {
                                response.setStatusCode(500).end();
                            } else {
                                response.end();
                            }
                        });
                    }
                }
            });

            vertx.createHttpServer().requestHandler(router::accept).listen(80);
        });
    }
}
