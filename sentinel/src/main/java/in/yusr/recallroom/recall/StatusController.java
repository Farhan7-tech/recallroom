package in.yusr.recallroom.recall;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** Read-only view of sentinel's records, for operators on the host (bound to loopback). */
@RestController
class StatusController {

    private final RecallStore store;

    StatusController(RecallStore store) {
        this.store = store;
    }

    @GetMapping("/recalls")
    List<Recall> recalls() {
        return store.all();
    }

    @GetMapping("/recalls/{id}")
    Optional<Recall> recall(@PathVariable String id) {
        return store.find(id);
    }
}
