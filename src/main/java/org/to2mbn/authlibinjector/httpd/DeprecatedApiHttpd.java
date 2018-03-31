package org.to2mbn.authlibinjector.httpd;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.to2mbn.authlibinjector.AuthlibInjector.debug;
import static org.to2mbn.authlibinjector.AuthlibInjector.info;
import static org.to2mbn.authlibinjector.util.IOUtils.asString;
import static org.to2mbn.authlibinjector.util.IOUtils.getURL;
import static org.to2mbn.authlibinjector.util.IOUtils.postURL;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.to2mbn.authlibinjector.YggdrasilConfiguration;
import org.to2mbn.authlibinjector.internal.org.json.JSONArray;
import org.to2mbn.authlibinjector.internal.org.json.JSONException;
import org.to2mbn.authlibinjector.internal.org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class DeprecatedApiHttpd extends NanoHTTPD {

	public static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

	// ^/MinecraftSkins/([^/]+)\.png$
	private static final Pattern URL_SKINS = Pattern.compile("^/MinecraftSkins/(?<username>[^/]+)\\.png$");

	private YggdrasilConfiguration configuration;

	public DeprecatedApiHttpd(int port, YggdrasilConfiguration configuration) {
		super("127.0.0.1", port);
		this.configuration = configuration;
	}

	@Override
	public Response serve(IHTTPSession session) {
		return processAsSkin(session)
				.orElseGet(() -> super.serve(session));
	}

	private Optional<Response> processAsSkin(IHTTPSession session) {
		Matcher matcher = URL_SKINS.matcher(session.getUri());
		if (!matcher.find()) return empty();
		String username = matcher.group("username");

		Optional<String> skinUrl;
		try {
			skinUrl = queryCharacterUUID(username)
					.flatMap(uuid -> queryCharacterProperty(uuid, "textures"))
					.map(encoded -> asString(Base64.getDecoder().decode(encoded)))
					.flatMap(texturesPayload -> obtainTextureUrl(texturesPayload, "SKIN"));
		} catch (UncheckedIOException | JSONException e) {
			info("[httpd] unable to fetch skin for {0}: {1}", username, e);
			return of(newFixedLengthResponse(Status.INTERNAL_ERROR, null, null));
		}

		if (skinUrl.isPresent()) {
			String url = skinUrl.get();
			debug("[httpd] retrieving skin for {0} from {1}", username, url);
			byte[] data;
			try {
				data = getURL(url);
			} catch (IOException e) {
				info("[httpd] unable to retrieve skin from {0}: {1}", url, e);
				return of(newFixedLengthResponse(Status.NOT_FOUND, null, null));
			}
			info("[httpd] retrieved skin for {0} from {1}, {2} bytes", username, url, data.length);
			return of(newFixedLengthResponse(Status.OK, "image/png", new ByteArrayInputStream(data), data.length));

		} else {
			info("[httpd] no skin found for {0}", username);
			return of(newFixedLengthResponse(Status.NOT_FOUND, null, null));
		}
	}

	private Optional<String> queryCharacterUUID(String username) throws UncheckedIOException, JSONException {
		String responseText;
		try {
			responseText = asString(postURL(
					configuration.getApiRoot() + "api/profiles/minecraft",
					CONTENT_TYPE_JSON,
					new JSONArray(new String[] { username })
							.toString().getBytes(UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		debug("[httpd] query uuid of username {0}, response: {1}", username, responseText);
		JSONArray response = new JSONArray(responseText);
		if (response.length() == 0) {
			return empty();
		} else if (response.length() == 1) {
			return of(response.getJSONObject(0).getString("id"));
		} else {
			throw new JSONException("Unexpected response length");
		}
	}

	private Optional<String> queryCharacterProperty(String uuid, String property) throws UncheckedIOException, JSONException {
		String responseText;
		try {
			responseText = asString(getURL(
					configuration.getApiRoot() + "sessionserver/session/minecraft/profile/" + uuid));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (responseText.isEmpty()) {
			debug("[httpd] query profile of {0}, not found", uuid);
			return empty();
		}
		debug("[httpd] query profile of {0}, response: {1}", uuid, responseText);
		JSONObject response = new JSONObject(responseText);
		for (Object element_ : response.getJSONArray("properties")) {
			JSONObject element = (JSONObject) element_;
			if (property.equals(element.getString("name"))) {
				return of(element.getString("value"));
			}
		}
		return empty();
	}

	private Optional<String> obtainTextureUrl(String texturesPayload, String textureType) throws JSONException {
		JSONObject textures = new JSONObject(texturesPayload).getJSONObject("textures");
		if (textures.has(textureType)) {
			return of(textures.getJSONObject(textureType).getString("url"));
		} else {
			return empty();
		}
	}

}