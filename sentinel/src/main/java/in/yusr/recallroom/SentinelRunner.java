package in.yusr.recallroom;

import in.yusr.recallroom.recall.ApprovalGate;
import in.yusr.recallroom.recall.RecallService;
import in.yusr.recallroom.switchapi.SwitchApi;
import in.yusr.recallroom.switchapi.SwitchSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Registers (first run only), connects to Switch and routes room messages to the recall workflow. */
@Component
class SentinelRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SentinelRunner.class);

    private final SwitchApi api;
    private final SwitchSocket socket;
    private final RecallService recalls;
    private final ApprovalGate gate;

    SentinelRunner(SwitchApi api, SwitchSocket socket, RecallService recalls, ApprovalGate gate) {
        this.api = api;
        this.socket = socket;
        this.recalls = recalls;
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Sentinel is Switch agent {}", api.identity());
        if (!gate.configured()) {
            log.warn("No QA Head is configured (recallroom.qa-heads): every approval will be refused");
        }
        socket.start(recalls::onMessage, recalls::onConnected);
    }
}
