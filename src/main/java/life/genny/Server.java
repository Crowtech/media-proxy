package life.genny;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.ext.web.FileUpload;
import io.vertx.rxjava.ext.web.Router;
import io.vertx.rxjava.ext.web.RoutingContext;
import io.vertx.rxjava.ext.web.handler.BodyHandler;
import io.vertx.rxjava.ext.web.handler.CorsHandler;
import life.genny.qwandautils.JsonUtils;
import life.genny.security.TokenIntrospection;

public class Server {

  private final static int serverPort;

  static {
    serverPort =
        Optional.ofNullable(System.getenv("MEDIA_PROXY_SERVER_PORT"))
            .map(Integer::valueOf).orElse(8080);
  }

  private static final String X_PINGARUNER = "X-PINGARUNER";
  private static final String X_REQUESTED_WITH = "X-Requested-With";
  private static final String X_TOTAL_COUNT = "X-Total-Count";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String ACCESS_CONTROL_ALLOW_ORIGIN =
      "Access-Control-Allow-Origin";
  private static final String CONTENT_RANGE = "Content-Range";

  public static CorsHandler corsHandler() {
    return CorsHandler.create("*").allowedHeader("*")
        .allowedMethod(HttpMethod.GET)
        .allowedMethod(HttpMethod.POST)
        .allowedMethod(HttpMethod.DELETE)
        .allowedMethod(HttpMethod.OPTIONS).allowedHeader(X_PINGARUNER)
        .allowedHeader(CONTENT_TYPE).allowedHeader(X_REQUESTED_WITH)
        .allowedHeader(ACCESS_CONTROL_ALLOW_ORIGIN)
        .allowedHeader(X_TOTAL_COUNT).exposedHeader(CONTENT_RANGE);
  }

  public static void run() {
    Vertx vertx = MonoVertx.getInstance().getVertx();
    Router router = Router.router(vertx);
    router.route().handler(corsHandler());
    /*
     * setBodyLimit expected a long that defines the number of bytes so a file of 100 kilobytes should
     * be written as 100000L
     */
    router.route().handler(BodyHandler.create().setBodyLimit(5000000L)
        .setDeleteUploadedFilesOnEnd(true));

    router.route(HttpMethod.POST, "/media")
        .blockingHandler(Server::userFileUploadHandler);

    router.route(HttpMethod.GET, "/media/:fileuuid")
        .blockingHandler(Server::userFindFileHandler);

    router.route(HttpMethod.POST, "/public")
        .blockingHandler(Server::publicFileUploadHandler);

    router.route(HttpMethod.GET, "/public/:fileuuid")
        .blockingHandler(Server::publicFindFileHandler);

    router.route(HttpMethod.DELETE, "/public/:fileuuid")
        .blockingHandler(Server::publicDeleteFileHandler);
	vertx.createHttpServer().requestHandler(router::accept).listen(serverPort);
  }


  public static void userFileUploadHandler(RoutingContext ctx) {
    List<String> roles = TokenIntrospection.setRoles("user");
    String tokenFromHeader = Minio.getTokenFromHeader(ctx);
    Vertx vertx = MonoVertx.getInstance().getVertx();
    Boolean isAllowed = TokenIntrospection.checkAuthForRoles(vertx, roles, tokenFromHeader);
    if(!isAllowed){
      ctx.response().setStatusCode(401).end();
    }
    else {
      Set<FileUpload> fileUploads = ctx.fileUploads();
      UUID userUUID = Minio.extractUserUUID(tokenFromHeader);
      List<Map<String,String>> fileObjects = fileUploads.stream().map(file -> {
        UUID fileUUID = Minio.saveOnStore(file,userUUID);
        Map<String,String> map = new HashMap<>();
        List<Map<String,String>> list = new ArrayList<>();
        map.put("name", file.fileName());
        map.put("uuid", fileUUID.toString() );
        list.add(map);
        return list;
      }).reduce((acc,element)->{
        acc.addAll(element);
        return acc;
      }).get();
      Map<String,List<Map<String,String>>> map = new HashMap<>();
      map.put("files", fileObjects);
      String json = JsonUtils.toJson(map);
      ctx.response().putHeader("Context-Type", "application/json").end(json);
    }
  }

  public static void userFindFileHandler(RoutingContext ctx) {
    List<String> roles = TokenIntrospection.setRoles("user");
    String tokenFromHeader = Minio.getTokenFromHeader(ctx);
    Vertx vertx = MonoVertx.getInstance().getVertx();
    Boolean isAllowed = TokenIntrospection.checkAuthForRoles(vertx, roles, tokenFromHeader);
    if(!isAllowed){
      ctx.response().setStatusCode(401).end();
    }
    else {
      UUID fileUUID = UUID.fromString(ctx.request().getParam("fileuuid"));
      UUID userUUID = Minio.extractUserUUID(tokenFromHeader);
      byte[] fetchFromStore = Minio.fetchFromStoreUserDirectory(fileUUID,userUUID);
      if(fetchFromStore.length == 0) {
        ctx.response().setStatusCode(404).end();
      }else {
        Buffer buffer = Buffer.buffer();
        for(byte e: fetchFromStore) {
          buffer.appendByte(e);
        }
        ctx.response().putHeader("Content-Type", "image/png").end(buffer);
      }
    }
  }

  public static void publicFileUploadHandler(RoutingContext ctx) {
    List<String> roles = TokenIntrospection.setRoles("user");
    String tokenFromHeader = Minio.getTokenFromHeader(ctx);
    Vertx vertx = MonoVertx.getInstance().getVertx();
    Boolean isAllowed = TokenIntrospection.checkAuthForRoles(vertx, roles, tokenFromHeader);
    if(!isAllowed){
      ctx.response().setStatusCode(401).end();
    }else {
      Set<FileUpload> fileUploads = ctx.fileUploads();
      List<Map<String,String>> fileObjects = fileUploads.stream().map(file -> {
        UUID fileUUID = Minio.saveOnStore(file);
        Map<String,String> map = new HashMap<>();
        List<Map<String,String>> list = new ArrayList<>();
        map.put("name", file.fileName());
        map.put("uuid", fileUUID.toString() );
        list.add(map);
        return list;
      }).reduce((acc,element)->{
        acc.addAll(element);
        return acc;
      }).get();
      Map<String,List<Map<String,String>>> map = new HashMap<>();
      map.put("files", fileObjects);
      String json = JsonUtils.toJson(map);
      ctx.response().putHeader("Context-Type", "application/json").end(json);
    }
  }

  public static void publicFindFileHandler(RoutingContext ctx) {
    UUID fileUUID = UUID.fromString(ctx.request().getParam("fileuuid"));
    byte[] fetchFromStore = Minio.fetchFromStorePublicDirectory(fileUUID);
    if(fetchFromStore.length == 0) {
      ctx.response().setStatusCode(404).end();
    }else {
      Buffer buffer = Buffer.buffer();
      for(byte e: fetchFromStore) {
        buffer.appendByte(e);
      }
      ctx.response().putHeader("Content-Type", "image/png").end(buffer);
    }
  }

  public static void publicDeleteFileHandler(RoutingContext ctx) {
    UUID fileUUID = UUID.fromString(ctx.request().getParam("fileuuid"));
    Minio.deleteFromStorePublicDirectory(fileUUID);
    ctx.response().end();
  }

}
