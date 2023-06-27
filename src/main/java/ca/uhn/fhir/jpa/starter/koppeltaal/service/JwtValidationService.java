/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import com.auth0.jwk.*;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 *
 */
@Service
public class JwtValidationService {

  private final String authServerIssuer;

  public JwtValidationService(@Value("${fhir.server.security.issuer:http://127.0.0.1:8083}") String authServerIssuer) {
    this.authServerIssuer = authServerIssuer;
  }

  /**
	 * Unfortunately, this implementation of JWT has no helper method for selecting the right
	 * algorithm from the header. The public key must match the algorithm type (RSA or EC), but
	 * the size of the hash algorithm can vary.
	 *
	 * @param publicKey
	 * @param algorithmName
	 * @return in instance of the {@link Algorithm} class.
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 * @throws IllegalArgumentException if the algorithmName is not one of RS{256,384,512} or ES{256,384,512}
	 */
	private static Algorithm getValidationAlgorithm(PublicKey publicKey, String algorithmName) throws IllegalArgumentException {
		if (StringUtils.startsWith(algorithmName, "RS")) {
			algorithmName = "RSA" + StringUtils.removeStart(algorithmName, "RS");
		}
		if (StringUtils.startsWith(algorithmName, "EC")) {
			algorithmName = "ECDSA" + StringUtils.removeStart(algorithmName, "EC");
		}
		Method[] declaredMethods = Algorithm.class.getDeclaredMethods();
		for (Method declaredMethod : declaredMethods) {
			if (declaredMethod.getParameterCount() == 2 && StringUtils.equals(declaredMethod.getName(), algorithmName)) {
				try {
					return (Algorithm) declaredMethod.invoke(null, new Object[]{publicKey, null});
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw new IllegalArgumentException(String.format("Problem calling method %s", declaredMethod.toString()));
				}
			}
		}
		throw new IllegalArgumentException(String.format("Unsupported algorithm %s", algorithmName));
	}

	public DecodedJWT validate(String token, String audience, long leeway) throws JwkException, JWTVerificationException {
		// Get the algorithm name from the JWT.
		DecodedJWT decode = JWT.decode(token);
		String algorithmName = decode.getAlgorithm();
		// Verify the issuer is the auth server
		JwkProvider provider = new UrlJwkProvider(authServerIssuer);
		Jwk jwk = provider.get(decode.getKeyId());
		Assert.isTrue(jwk != null, String.format("Unable to locate public key for issuer %s", authServerIssuer));

		// Get the algorithm from the public key and algorithm name.
		Algorithm algorithm = getValidationAlgorithm(jwk.getPublicKey(), algorithmName);

		// Decode and verify the token.
		return JWT.require(algorithm)
				.withIssuer(authServerIssuer)
				.withAudience(audience) // Make sure to require yourself to be the audience.
				.acceptExpiresAt(leeway)
				.build()
				.verify(token);
	}

}
