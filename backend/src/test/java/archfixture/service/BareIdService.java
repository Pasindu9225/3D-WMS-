package archfixture.service;

/** Deliberately broken fixture for ArchitectureRulesSelfTest. */
public class BareIdService {

    public interface FakeRepo {
        Object findById(Long id);
    }

    private final FakeRepo repo;

    public BareIdService(FakeRepo repo) {
        this.repo = repo;
    }

    public Object load(Long id) {
        return repo.findById(id);
    }
}
