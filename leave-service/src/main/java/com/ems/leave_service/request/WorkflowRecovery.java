package com.ems.leave_service.request;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ems.leave.workflow.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowRecovery {
    private final LeaveWorkflowService workflows;

    public WorkflowRecovery(LeaveWorkflowService workflows) {
        this.workflows = workflows;
    }

    @Scheduled(fixedDelayString = "${ems.leave.workflow.poll-ms:1000}")
    public void recover() {
        for (var w : workflows.due()) {
            try {
                workflows.advance(w.id(), w.stepId());
            } catch (RuntimeException e) {
                workflows.failed(w.id(), w.stepId());
            }
        }
    }
}
