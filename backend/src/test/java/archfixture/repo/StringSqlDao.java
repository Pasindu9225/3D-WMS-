package archfixture.repo;

import jakarta.persistence.EntityManager;

/** Deliberately broken fixture for ArchitectureRulesSelfTest: SQL built by concatenation. */
public class StringSqlDao {

    private final EntityManager em;

    public StringSqlDao(EntityManager em) {
        this.em = em;
    }

    public Object find(String email) {
        return em.createNativeQuery("SELECT * FROM app_user WHERE email = '" + email + "'").getResultList();
    }
}
