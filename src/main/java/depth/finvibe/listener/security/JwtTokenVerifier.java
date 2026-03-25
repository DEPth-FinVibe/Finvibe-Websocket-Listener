package depth.finvibe.listener.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class JwtTokenVerifier {

	@Value("${jwt.secret}")
	private String secretKey;

	@Value("${jwt.issuer}")
	private String issuer;

	private SecretKey key;

	@PostConstruct
	public void init() {
		key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
	}

	public UUID verifyAndGetUserId(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(key)
				.requireIssuer(issuer)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		return UUID.fromString(claims.getSubject());
	}
}
