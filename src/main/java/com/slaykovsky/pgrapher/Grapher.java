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
    private final String HEADER_CONTENT_TYPE = "content-type";
    private final String HEADER_ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private final String APPLICATION_JSON = "application/json; charset=utf-8";
    private final String STAR = "*";
    private final String WEBROOT = "webroot";

    private final String DATABASE_HOST = "database";
    private final String DATABASE_NAME = "pgrapher";
    private final String DATABASE_USER = "pgrapher";
    private final String DATABASE_PASS = "password";

    private final String INIT_DB = "CREATE TABLE IF NOT EXISTS tests " +
            "(id SERIAL, hostname TEXT, test TEXT, threads INT, run INT, result REAL)";
    private final String SELECT_HOSTNAMES = "SELECT DISTINCT ON (hostname) hostname FROM tests";
    private final String SELECT_TESTS = "SELECT hostname, test, threads, avg(result) " +
            "AS average_result FROM tests GROUP BY hostname, test, threads ORDER BY test";
    private final String SELECT_ALL_HOSTNAME_TESTS = "SELECT test, threads, avg(result) AS average_result " +
            "FROM tests WHERE hostname = ? GROUP BY test, threads ORDER BY test";
    private final String DELETE_TEST = "DELETE FROM tests WHERE id = ?";
    private final String INSERT_TEST = "INSERT INTO tests (hostname, test, threads, run, result) " +
            "VALUES (?, ?, ?, ?, ?)";

    private final JsonObject postgreSQLClientConfig = new JsonObject()
            .put("host", DATABASE_HOST)
            .put("username", DATABASE_USER)
            .put("password", DATABASE_PASS)
            .put("database", DATABASE_NAME);

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

    private void makeQuery(SQLConnection conn, HttpServerResponse response, String queryString) {
        conn.query(queryString, query -> {
            if (query.failed()) {
                response.setStatusCode(500).end();
            }
            if (query.result().getNumRows() == 0) {
                response.setStatusCode(404).end();
            } else {
                JsonArray array = new JsonArray();
                query.result().getRows().forEach(array::add);
                response.putHeader(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                        .putHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, STAR)
                        .end(array.encodePrettily());
            }
        });
    }

    @Override
    public void start(Future<Void> future) {
        client = PostgreSQLClient.createShared(vertx, postgreSQLClientConfig);
        initialData(ready -> {
            Router router = Router.router(vertx);

            router.route().handler(BodyHandler.create());
            router.route("/static/*").handler(StaticHandler.create(WEBROOT));

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

                makeQuery(conn, response, SELECT_HOSTNAMES);
            });


            router.get("/api/tests").handler(ctx -> {
                HttpServerResponse response = ctx.response();
                SQLConnection conn = ctx.get("conn");

                makeQuery(conn, response, SELECT_TESTS);
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
                            response.putHeader(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                                    .putHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, STAR)
                                    .end(query.result().getRows().get(0).encodePrettily());
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
