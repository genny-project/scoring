package life.genny.scoring;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bitsofinfo.hazelcast.discovery.docker.swarm.SwarmAddressPicker;
import org.bitsofinfo.hazelcast.discovery.docker.swarm.SystemPrintLogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.AddressPicker;
import com.hazelcast.instance.DefaultNodeContext;
import com.hazelcast.instance.HazelcastInstanceFactory;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeContext;

import io.vertx.core.Future;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEventType;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.rxjava.core.AbstractVerticle;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.eventbus.EventBus;
import io.vertx.rxjava.core.eventbus.Message;
import io.vertx.rxjava.core.eventbus.MessageProducer;
import io.vertx.rxjava.ext.auth.oauth2.AccessToken;
import io.vertx.rxjava.ext.auth.oauth2.OAuth2Auth;
import io.vertx.rxjava.ext.auth.oauth2.providers.KeycloakAuth;
import io.vertx.rxjava.ext.web.Router;
import io.vertx.rxjava.ext.web.RoutingContext;
import io.vertx.rxjava.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.rxjava.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import life.genny.qwanda.entity.BaseEntity;
import rx.Observable;

public class ServiceVerticle extends AbstractVerticle {

	private static final Logger logger = LoggerFactory.getLogger(ServiceVerticle.class);

	private EventBus eventBus = null;
	MessageProducer<JsonObject> msgToFrontEnd;
	Observable<Message<Object>> events;
	Observable<Message<Object>> cmds;
	Observable<Message<Object>> data;

	JsonObject keycloakJson;
	AccessToken tokenAccessed;

	private OAuth2Auth oauth2;
	String token;

	Map<String, String> keycloakJsonMap = new HashMap<String, String>();

	Gson gson = new GsonBuilder().registerTypeAdapter(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
		@Override
		public LocalDateTime deserialize(JsonElement json, Type type,
				JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
			return ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()).toLocalDateTime();
		}

		public JsonElement serialize(LocalDateTime date, Type typeOfSrc, JsonSerializationContext context) {
			return new JsonPrimitive(date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)); // "yyyy-mm-dd"
		}
	}).create();

	@Override
	public void start() {

		setupCluster();
		// createAuth2();

		Future<Void> fut = Future.future();
		runRouters().compose(i -> {
			fut.complete();
		}, fut);
	}

	public void setupCluster() {
		Future<Void> startFuture = Future.future();
		createCluster().compose(v -> {
			eventListeners();
			eventsInOutFromCluster();

			startFuture.complete();
		}, startFuture);
	}

	public Future<Void> createCluster() {
		Future<Void> startFuture = Future.future();

		vertx.<HazelcastInstance>executeBlocking(future -> {

			if (System.getenv("SWARM") != null) {

				Config conf = new ClasspathXmlConfig("bridge.xml");
				System.out.println("Starting hazelcast Scoring DISCOVERY!!!!!");
				NodeContext nodeContext = new DefaultNodeContext() {
					@Override
					public AddressPicker createAddressPicker(Node node) {
						return new SwarmAddressPicker(new SystemPrintLogger());
					}
				};

				HazelcastInstance hazelcastInstance = HazelcastInstanceFactory.newHazelcastInstance(conf, "bridge",
						nodeContext);
				System.out.println("Done hazelcast DISCOVERY");
				future.complete(hazelcastInstance);
			} else {
				future.complete(null);
			}
		}, res -> {
			if (res.succeeded()) {
				System.out.println("RESULT SUCCEEDED");
				HazelcastInstance hazelcastInstance = (HazelcastInstance) res.result();
				ClusterManager mgr = null;
				if (hazelcastInstance != null) {
					mgr = new HazelcastClusterManager(hazelcastInstance);
				} else {
					mgr = new HazelcastClusterManager(); // standard docker
				}
				System.out.println("Starting Clustered Vertx");
				VertxOptions options = new VertxOptions().setClusterManager(mgr);

				if (System.getenv("SWARM") == null) {
					if (System.getenv("GENNYDEV") == null) {
						System.out.println("setClusterHost etc");
						options.setClusterHost("scoring").setClusterPublicHost("scoring").setClusterPort(15701);
					} else {
						logger.info("Running DEV mode, no cluster");
						options.setBlockedThreadCheckInterval(200000000);
						options.setMaxEventLoopExecuteTime(Long.MAX_VALUE);
					}

				}

				Vertx.clusteredVertx(options, res2 -> {
					if (res2.succeeded()) {
						eventBus = res2.result().eventBus();
						// handler.setEventBus(eventBus);
						System.out.println("Scoring Cluster Started!");
						startFuture.complete();
					} else {
						// failed!
					}
				});
			}
		});

		return startFuture;
	}

	public Future<Void> runRouters() {
		System.out.println("Setting up routes");
		Future<Void> fut = Future.future();
		Router router = Router.router(vertx);
		router.route(HttpMethod.POST, "/api/service/score").handler(this::apiServiceHandler);

		vertx.createHttpServer().requestHandler(router::accept).listen(8085);
		fut.complete();
		return fut;
	}

	public Future<Void> securityProviderReader() {
		System.out.println("Security Provider Reader");
		Future<Void> fut1 = Future.future();
		vertx.fileSystem().readFile("realm/keycloak.json", d -> {
			if (!d.failed()) {
				keycloakJson = d.result().toJsonObject();
				fut1.complete();
				System.out.println(keycloakJson);
			} else {
				System.err.println("Error reading keycloak.json file!");
			}
		});
		return fut1;
	}

	public void createAuth2() {
		logger.info("Creating OAUTH2");
		Future<Void> startFuture = Future.future();
		securityProviderReader().compose(v -> {
			oauth2 = KeycloakAuth.create(vertx, OAuth2FlowType.PASSWORD, keycloakJson);
			startFuture.completer();
		}, startFuture);
	}

	public void checkToken(RoutingContext routingContext) {
		// String authToken = routingContext.request().getHeader("Authorization");
		// for (String header : routingContext.request().headers().names()) {
		// System.out.println("headers="+header+":"+routingContext.request().headers().get(header));
		// }
		if (routingContext.get("token") != null) {
			System.out.println("TOKEN NOT NULL IN CHECK TOKEN");
			routingContext.next();
		} else {
			System.out.println("TOKEN IS  NULL IN CHECK TOKEN");

		}

		String token = routingContext.request().getParam("token");
		if ((token == null) || (token.isEmpty())) {
			token = this.token; // cheat to get around lacl of token from alyson
		} else {
			this.token = token;
		}
		System.out.println(
				"Token to be checked=" + token.substring(0, 10) + "..." + token.substring(token.length() - 10));
		oauth2.introspectToken(token, res -> {
			if (res.succeeded()) {
				// token is valid!
				tokenAccessed = res.result();
				System.out.println("TokenAccessed:" + tokenAccessed.principal().toString());

				System.out.println("PASSED TOKEN SUCCEED " + tokenAccessed.principal().toString().substring(0, 10)
						+ "..." + tokenAccessed.principal().toString()
								.substring(tokenAccessed.principal().toString().length() - 10));

				routingContext.put("token", "true");
				// tokenAccessed.principal()
				String username = tokenAccessed.principal().getString("preferred_username");
				System.out.println("Username=" + username);
				routingContext.next();
			} else {
				System.err.println("PASSED TOKEN FAILED ");
				routingContext.response().setStatusCode(403).end();
			}
		});
		// routingContext.next();
		// routingContext.response().setStatusCode(403).end();
	}

	public void eventListeners() {
		events = eventBus.consumer("events").toObservable();
		cmds = eventBus.consumer("cmds").toObservable();
		data = eventBus.consumer("data").toObservable();
	}


	/*
	 * Write any cmds or data out to the frontend
	 */
	public void eventsInOutFromCluster() {
		cmds.subscribe(arg -> {
			String incomingCmd = arg.body().toString();
			logger.info(incomingCmd);
			if (!incomingCmd.contains("<body>Unauthorized</body>")) {
				JsonObject json = new JsonObject(incomingCmd); // Buffer.buffer(arg.toString().toString()).toJsonObject();
			} else {
				logger.error("Cmd with Unauthorised data received");
			}
		});
		data.subscribe(arg -> {
			String incomingData = arg.body().toString();
			logger.info(incomingData);
			JsonObject json = new JsonObject(incomingData); // Buffer.buffer(arg.toString().toString()).toJsonObject();
			
			// msgToFrontEnd.write(Buffer.buffer(arg.body().toString()).toJsonObject());
		});
	}





	public void apiServiceHandler(RoutingContext routingContext) {

		routingContext.request().bodyHandler(body -> {
			//
			JsonObject j = body.toJsonObject();
			System.out.println("Score ->"+j);
			BaseEntity be1 = new BaseEntity("PER_1","Bill");
			BaseEntity be2 = new BaseEntity("PER_2","Ben");
			Double score = calculateScore(be1,be2);
			routingContext.response().end(score+"");
		});
		
	}



	private ErrorHandler errorHandler() {
		return ErrorHandler.create(true);
	}

	private StaticHandler staticHandler() {
		return StaticHandler.create().setCachingEnabled(false);
	}


	private Double calculateScore(BaseEntity be1, BaseEntity be2)
	{
		Double ret = 0.0;
		// Data needs to be in array format of BaseEntities
		
		// For now, score just two be against each other
		
		// go through each attribute and calculate score
		
		    // for each attribute apply weightings using standard decision matrix score
		
	//	return score
		return ret;
	}
	
}
