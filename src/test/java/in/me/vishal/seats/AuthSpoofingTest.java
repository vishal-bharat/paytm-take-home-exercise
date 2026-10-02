package in.me.vishal.seats;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSpoofingTest extends IntegrationTestBase {

    @Test
    void reserveWithoutValidToken_is401() {
        long show = createShow(4, "A1");
        assertThat(post("/shows/" + show + "/reserve", "{\"seats\":[\"A1\"]}", Map.of()).status()).isEqualTo(401);
        assertThat(reserve("not-a-jwt", show, "{\"seats\":[\"A1\"]}").status()).isEqualTo(401);
        assertThat(counts(show)).containsExactly(1, 0, 0, 1);
    }

    @Test
    void tamperedTokenPayload_is401() {
        long show = createShow(4, "A1");
        String[] parts = token(unique("attacker")).split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"victim\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(reserve(forged, show, "{\"seats\":[\"A1\"]}").status()).isEqualTo(401);
    }

    @Test
    void bodyUserIdIgnored_andOnlyOwnerCanCancel() {
        long show = createShow(4, "A1");
        String victim = unique("victim");
        String attacker = unique("attacker");
        String victimToken = token(victim);
        String attackerToken = token(attacker);

        Response r = reserve(attackerToken, show, "{\"seats\":[\"A1\"],\"user_id\":\"" + victim + "\"}");
        assertThat(r.status()).isEqualTo(201);
        String rid = r.field("reservation_id");

        assertThat(cancel(victimToken, rid).status()).isEqualTo(404);   
        assertThat(counts(show)).containsExactly(0, 0, 1, 1);
        assertThat(cancel(attackerToken, rid).status()).isEqualTo(200); 
        assertThat(counts(show)).containsExactly(1, 0, 0, 1);
    }

    @Test
    void createShowWithoutAdminKey_is401() {
        Response r = post("/shows", "{\"price_paise\":100,\"seats\":[\"A1\"]}", Map.of());
        assertThat(r.status()).isEqualTo(401);
    }
}
