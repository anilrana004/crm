package com.securetravels.crm.communications.template;

import com.securetravels.crm.communications.thread.CommunicationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelTemplateRepository extends JpaRepository<ChannelTemplate, UUID> {

    Optional<ChannelTemplate> findByChannelAndCode(CommunicationChannel channel, String code);

    List<ChannelTemplate> findByChannelOrderByLabelAsc(CommunicationChannel channel);

    /**
     * Templates an operator may actually pick right now.
     *
     * <p>Separate from {@link #findByChannelOrderByLabelAsc(CommunicationChannel)}
     * so a disabled template disappears from the picker while the admin
     * catalogue can still show it. A disabled template is also rejected by the
     * dispatcher, so this narrows what callers offer rather than what is legal.
     *
     * <p>Does not filter on approval: a PENDING template is excluded by the
     * dispatcher too, but the admin list needs to show it to explain why.
     */
    List<ChannelTemplate> findByChannelAndEnabledTrueOrderByLabelAsc(CommunicationChannel channel);
}
