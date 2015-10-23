package io.github.infolis.datastore;

import io.github.infolis.InfolisConfig;
import io.github.infolis.model.BaseModel;
import io.github.infolis.model.ErrorResponse;
import io.github.infolis.model.Execution;
import io.github.infolis.model.entity.InfolisFile;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

/**
 * Client to access the Linked Data frontend web services.
 * 
 * @author kba
 *
 */
class CentralClient extends AbstractClient {

	private Logger log = LoggerFactory.getLogger(CentralClient.class);

	@SuppressWarnings("rawtypes")
	private static Map<Class, String> uriForClass = new HashMap<>();
	static {
		/*
		 * Add mappings from class name to uri fragment here, e.g.
		 * http://infolis-frontend/api/file/124 
		 *                             \__/
		 *                            map this
		 */
		uriForClass.put(InfolisFile.class, "file");
		uriForClass.put(Execution.class, "execution");
	}

	private static <T extends BaseModel> String getUriForClass(Class<T> clazz) {
		if (uriForClass.containsKey(clazz)) {
			return uriForClass.get(clazz);
		} else {
			String name = clazz.getSimpleName();
			return name.substring(0,1).toLowerCase() + name.substring(1);
		}
	}

	private static Client jerseyClient = ClientBuilder.newBuilder()
			.property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true)
			.register(JacksonFeature.class)
			.register(MultiPartFeature.class)
			.register(JacksonJsonProvider.class)
			.build();

	@Override
	public <T extends BaseModel> void post(Class<T> clazz, T thing) throws BadRequestException {
		WebTarget target = jerseyClient
				.target(InfolisConfig.getFrontendURI())
				.path(CentralClient.getUriForClass(clazz));
		Entity<T> entity = Entity
				.entity(thing, MediaType.APPLICATION_JSON_TYPE);
		Response resp = target
				.request(MediaType.APPLICATION_JSON_TYPE)
				.post(entity);
		log.debug("-> {}", target);
		log.debug("<- HTTP {}", resp.getStatus());
		if (resp.getStatus() >= 400) {
			// TODO check whether resp actually succeeded
			ErrorResponse err = resp.readEntity(ErrorResponse.class);
			log.error(err.getMessage());
			log.error(Arrays.toString(err.getCause().entrySet().toArray()));
			throw new BadRequestException(resp + err.getMessage());
		} else {
			thing.setUri(resp.getHeaderString("Location"));
			log.debug("URI of Posted {}: {}", clazz.getSimpleName(), thing.getUri());
		}
	}

	@Override
	public <T extends BaseModel> void put(Class<T> clazz, T thing) {
		String thingURI = thing.getUri();
		if (null == thingURI) {
			throw new IllegalArgumentException("PUT requires a URI: " + thing);
		}
		WebTarget target = jerseyClient.target(URI.create(thingURI));
		Entity<T> entity = Entity
				.entity(thing, MediaType.APPLICATION_JSON_TYPE);
		Response resp = target
				.request(MediaType.APPLICATION_JSON_TYPE)
				.put(entity);
		if (resp.getStatus() >= 400) {
			// TODO check whether resp actually succeeded
//			ErrorResponse err = resp.readEntity(ErrorResponse.class);
//			log.error(err.getMessage());
//			log.error(Arrays.toString(err.getCause().entrySet().toArray()));
			throw new BadRequestException(resp);
		}

	}


	@Override
	public <T extends BaseModel> T get(Class<T> clazz, String uriStr) {
		URI uri = URI.create(uriStr);
		log.debug("GET from {}", uri);
		if (!uri.isAbsolute()) {
			String msg = "URI must be absolute, " + uri + " is NOT.";
			log.error(msg);
			throw new ProcessingException(msg);
		}
		WebTarget target = jerseyClient.target(uri);
		Response resp = target.request(MediaType.APPLICATION_JSON_TYPE).get();
		log.debug("-> HTTP {}", resp.getStatus());
		if (resp.getStatus() == 404) {
			throw new RuntimeException(String.format("No such '%s': '%s'", clazz.getSimpleName(), target.getUri()));
		} else if (resp.getStatus() >= 400) {
			throw new WebApplicationException(resp);
		}
		T thing;
		try {
			thing = resp.readEntity(clazz);
		} catch (Exception e) {
			throw new ProcessingException(e);
		}

		thing.setUri(target.getUri().toString());
		return thing;
	}

	@Override
	public <T extends BaseModel> List<T> get(Class<T> clazz, Iterable<String> uriStrList) throws BadRequestException, ProcessingException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void clear() {
		log.error("clear not supported");
		return;
	}

	@Override
	public void dump(Path directory) {
		log.error("dump not supported");
		return;
	}

}
