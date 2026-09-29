package com.securetravels.crm.communications.template;

import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read/approve the cross-channel template catalogue. */
@Service
public class ChannelTemplateService {

    private final ChannelTemplateRepository templates;

    public ChannelTemplateService(ChannelTemplateRepository templates) {
        this.templates = templates;
    }

    @Transactional(readOnly = true)
    public Optional<ChannelTemplate> find(CommunicationChannel channel, String code) {
        return templates.findByChannelAndCode(channel, code);
    }

    @Transactional(readOnly = true)
    public ChannelTemplate require(CommunicationChannel channel, String code) {
        return find(channel, code)
                .orElseThrow(() -> new NotFoundException(
                        "No " + channel + " template with code " + code));
    }

    /**
     * The purpose a template actually carries. The dispatcher uses this, never
     * the caller's label, so a MARKETING email cannot be sent as transactional.
     */
    @Transactional(readOnly = true)
    public Purpose purposeOf(CommunicationChannel channel, String code) {
        return require(channel, code).getCategory();
    }

    @Transactional(readOnly = true)
    public List<ChannelTemplate> list(CommunicationChannel channel) {
        return templates.findByChannelOrderByLabelAsc(channel);
    }

    @Transactional
    public ChannelTemplate setApproval(UUID id, ChannelTemplate.ApprovalStatus status) {
        ChannelTemplate template = templates.findById(id)
                .orElseThrow(() -> new NotFoundException("Template not found: " + id));
        template.setApprovalStatus(status);
        return templates.save(template);
    }
}
