package top.sharehome.springbootinittemplate.config.ai.spring.service.chat.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * 深度思考流解析器
 *
 * @author AntonyCheng
 */
public class ReasonStreamParser {

    // ----------------------------------------------------------------
    // 构造参数
    // ----------------------------------------------------------------

    /**
     * 是否为思维链模式。
     * false → 完全透传，所有内容实时输出到 reply，isOrphanEndTag 此时无意义。
     */
    private final boolean isReasoningMode;

    /**
     * 是否为孤立结束标签模式（仅在 isReasoningMode=true 时生效）。
     * true  → 以孤立的 {@code </think>} 作为分隔符，之前内容为 think，之后为 reply。
     * false → 标准 {@code <think>...</think>} 模式。
     */
    private final boolean isOrphanEndTag;

    // ----------------------------------------------------------------
    // 内容缓冲区
    // ----------------------------------------------------------------

    private final StringBuilder thinkContent = new StringBuilder();
    private final StringBuilder replyContent = new StringBuilder();

    /**
     * 孤立标签模式下，在遇到 {@code </think>} 之前内容暂存于此。
     * 遇到 {@code </think>} 后整体转移到 thinkContent；
     * 流结束仍未遇到则整体转移到 replyContent。
     */
    private final StringBuilder pendingBuffer = new StringBuilder();

    // ----------------------------------------------------------------
    // 增量位置追踪（供 getXxxIncrement() 方法使用）
    // ----------------------------------------------------------------

    private int lastRetrievedThinkLength = 0;
    private int lastRetrievedReplyLength = 0;

    // ----------------------------------------------------------------
    // 标准模式状态
    // ----------------------------------------------------------------

    private boolean foundFirstThinkStart = false;
    private boolean thinkTagComplete = false;
    private int thinkTagDepth = 0;

    // 用于触发增量回调的长度记录
    private int lastThinkContentLength = 0;
    private int lastReplyContentLength = 0;

    // ----------------------------------------------------------------
    // 孤立标签模式状态
    // ----------------------------------------------------------------

    /**
     * 孤立标签模式下的阶段：
     * PENDING  → 尚未遇到 {@code </think>}，内容暂存 pendingBuffer
     * REPLY    → 已遇到 {@code </think>}，后续内容直接写入 replyContent
     */
    private enum OrphanPhase {
        PENDING, REPLY
    }

    private OrphanPhase orphanPhase = OrphanPhase.PENDING;

    // ----------------------------------------------------------------
    // 标签匹配状态机（标准模式 & 孤立标签模式共用）
    // ----------------------------------------------------------------

    private enum TagState {
        NORMAL,
        MATCHING_START,
        MATCHING_END
    }

    private TagState currentState = TagState.NORMAL;
    private final StringBuilder tagBuffer = new StringBuilder();

    // ----------------------------------------------------------------
    // 标签常量
    // ----------------------------------------------------------------

    private static final String START_TAG = "<think>";
    private static final String END_TAG = "</think>";

    // ----------------------------------------------------------------
    // 构造方法
    // ----------------------------------------------------------------

    public ReasonStreamParser(boolean isReasoningMode, boolean isOrphanEndTag) {
        this.isReasoningMode = isReasoningMode;
        // 非思维链模式下，孤立标签参数强制为 false，避免歧义
        this.isOrphanEndTag = isReasoningMode && isOrphanEndTag;
    }

    // ----------------------------------------------------------------
    // 公开入口
    // ----------------------------------------------------------------

    /**
     * 处理流式输入的每个数据块
     *
     * @param chunk 接收到的数据块
     */
    public void processChunk(String chunk) {
        if (StringUtils.isEmpty(chunk)) {
            return;
        }
        for (char c : chunk.toCharArray()) {
            processChar(c);
        }
    }

    /**
     * 流结束时调用。
     * 孤立标签模式下，若始终未遇到 {@code </think>}，将 pendingBuffer 整体作为 reply 释放。
     */
    public void finish() {
        if (isReasoningMode && isOrphanEndTag && orphanPhase == OrphanPhase.PENDING) {
            // 先把 tagBuffer 里可能残留的内容也冲入 pendingBuffer
            if (tagBuffer.length() > 0) {
                pendingBuffer.append(tagBuffer);
                tagBuffer.setLength(0);
            }
            // 整体作为 reply 释放
            flushPendingToReply();
        } else {
            // 标准模式 / 非思维链模式：tagBuffer 里残留内容按当前状态冲出
            if (tagBuffer.length() > 0) {
                flushTagBufferBasedOnState();
            }
        }
    }

    // ----------------------------------------------------------------
    // 字符级分发
    // ----------------------------------------------------------------

    private void processChar(char c) {
        if (!isReasoningMode) {
            // 非思维链模式：完全透传
            appendToReply(c);
            return;
        }
        if (isOrphanEndTag) {
            processCharOrphan(c);
        } else {
            processCharStandard(c);
        }
    }

    // ----------------------------------------------------------------
    // 孤立标签模式处理
    // ----------------------------------------------------------------

    /**
     * 孤立标签模式下的字符处理
     */
    private void processCharOrphan(char c) {
        if (orphanPhase == OrphanPhase.REPLY) {
            // 已找到 </think>，后续直接写入 reply
            appendToReply(c);
            return;
        }

        // PENDING 阶段：监听 </think>
        switch (currentState) {
            case NORMAL:
                if (c == '<') {
                    tagBuffer.setLength(0);
                    tagBuffer.append(c);
                    currentState = TagState.MATCHING_START;
                } else {
                    pendingBuffer.append(c);
                }
                break;

            case MATCHING_START:
                tagBuffer.append(c);
                if ("</".contentEquals(tagBuffer)) {
                    currentState = TagState.MATCHING_END;
                } else if (END_TAG.startsWith(tagBuffer.toString())) {
                    // 继续等待，可能是 </think> 的前缀
                } else {
                    // 不是 </think>，冲入 pendingBuffer
                    pendingBuffer.append(tagBuffer);
                    tagBuffer.setLength(0);
                    currentState = TagState.NORMAL;
                }
                break;

            case MATCHING_END:
                tagBuffer.append(c);
                if (tagBuffer.toString().equals(END_TAG)) {
                    // 命中孤立 </think>
                    handleOrphanEndTag();
                    currentState = TagState.NORMAL;
                    tagBuffer.setLength(0);
                } else if (!END_TAG.startsWith(tagBuffer.toString())) {
                    // 不匹配，冲入 pendingBuffer
                    pendingBuffer.append(tagBuffer);
                    tagBuffer.setLength(0);
                    currentState = TagState.NORMAL;
                }
                break;
        }
    }

    /**
     * 命中孤立 {@code </think>}：将 pendingBuffer 整体转为 thinkContent，切换到 REPLY 阶段
     */
    private void handleOrphanEndTag() {
        orphanPhase = OrphanPhase.REPLY;
        // pendingBuffer 整体作为 think 内容
        if (pendingBuffer.length() > 0) {
            thinkContent.append(pendingBuffer);
            pendingBuffer.setLength(0);
            // 触发 think 增量回调（批量）
            triggerThinkContentIncrement();
        }
        // 通知 think 内容完成
        onThinkContentComplete(thinkContent.toString());
    }

    /**
     * 流结束仍未遇到 {@code </think>}：pendingBuffer 整体作为 reply
     */
    private void flushPendingToReply() {
        if (pendingBuffer.length() > 0) {
            replyContent.append(pendingBuffer);
            pendingBuffer.setLength(0);
            triggerReplyContentIncrement();
        }
    }

    // ----------------------------------------------------------------
    // 标准模式处理（保留原有逻辑）
    // ----------------------------------------------------------------

    private void processCharStandard(char c) {
        if (!thinkTagComplete) {
            processCharInThinkPhase(c);
        } else {
            appendToReply(c);
        }
    }

    private void processCharInThinkPhase(char c) {
        switch (currentState) {
            case NORMAL:
                if (c == '<') {
                    tagBuffer.setLength(0);
                    tagBuffer.append(c);
                    currentState = TagState.MATCHING_START;
                } else {
                    if (!foundFirstThinkStart) {
                        appendToReply(c);
                    } else {
                        addToCurrentContent(c);
                    }
                }
                break;

            case MATCHING_START:
                tagBuffer.append(c);
                if (tagBuffer.toString().equals(START_TAG)) {
                    handleStartTag();
                    currentState = TagState.NORMAL;
                    tagBuffer.setLength(0);
                } else if ("</".contentEquals(tagBuffer)) {
                    currentState = TagState.MATCHING_END;
                } else if (!START_TAG.startsWith(tagBuffer.toString()) && !END_TAG.startsWith(tagBuffer.toString())) {
                    flushTagBufferBasedOnState();
                    currentState = TagState.NORMAL;
                }
                break;

            case MATCHING_END:
                tagBuffer.append(c);
                if (tagBuffer.toString().equals(END_TAG)) {
                    handleEndTag();
                    currentState = TagState.NORMAL;
                    tagBuffer.setLength(0);
                } else if (!END_TAG.startsWith(tagBuffer.toString())) {
                    flushTagBufferBasedOnState();
                    currentState = TagState.NORMAL;
                }
                break;
        }
    }

    private void handleStartTag() {
        if (!foundFirstThinkStart) {
            foundFirstThinkStart = true;
            thinkTagDepth = 1;
        } else {
            thinkContent.append(START_TAG);
            thinkTagDepth++;
            triggerThinkContentIncrement();
        }
    }

    private void handleEndTag() {
        if (foundFirstThinkStart) {
            thinkTagDepth--;
            if (thinkTagDepth == 0) {
                thinkTagComplete = true;
                onThinkContentComplete(thinkContent.toString());
            } else {
                thinkContent.append(END_TAG);
                triggerThinkContentIncrement();
            }
        } else {
            for (char c : END_TAG.toCharArray()) {
                appendToReply(c);
            }
        }
    }

    private void addToCurrentContent(char c) {
        if (foundFirstThinkStart && !thinkTagComplete) {
            thinkContent.append(c);
            triggerThinkContentIncrement();
        } else if (thinkTagComplete) {
            appendToReply(c);
        }
    }

    // ----------------------------------------------------------------
    // 公共写入 & 回调触发
    // ----------------------------------------------------------------

    /**
     * 将字符写入 replyContent 并触发增量回调
     */
    private void appendToReply(char c) {
        replyContent.append(c);
        triggerReplyContentIncrement();
    }

    private void triggerThinkContentIncrement() {
        int currentLength = thinkContent.length();
        if (currentLength > lastThinkContentLength) {
            String increment = thinkContent.substring(lastThinkContentLength, currentLength);
            lastThinkContentLength = currentLength;
            onThinkContentIncrement(increment);
        }
    }

    private void triggerReplyContentIncrement() {
        int currentLength = replyContent.length();
        if (currentLength > lastReplyContentLength) {
            String increment = replyContent.substring(lastReplyContentLength, currentLength);
            lastReplyContentLength = currentLength;
            onReplyContentIncrement(increment);
            onReplyContentUpdate(replyContent.toString());
        }
    }

    private void flushTagBufferBasedOnState() {
        String buffered = tagBuffer.toString();
        for (char c : buffered.toCharArray()) {
            if (!foundFirstThinkStart) {
                appendToReply(c);
            } else {
                addToCurrentContent(c);
            }
        }
        tagBuffer.setLength(0);
    }

    // ----------------------------------------------------------------
    // 可重写的回调方法
    // ----------------------------------------------------------------

    /**
     * think 内容完成时的回调
     *
     * @param thinkContent 完整的思考内容
     */
    public void onThinkContentComplete(String thinkContent) {
    }

    /**
     * think 内容增量回调（每次新增内容时触发）
     *
     * @param increment 新增的思考内容
     */
    public void onThinkContentIncrement(String increment) {
    }

    /**
     * 回复内容更新时的回调（每次新增时携带完整内容）
     *
     * @param replyContent 当前完整的回复内容
     */
    public void onReplyContentUpdate(String replyContent) {
    }

    /**
     * 回复内容增量回调（每次新增内容时触发）
     *
     * @param increment 新增的回复内容
     */
    public void onReplyContentIncrement(String increment) {
    }

    // ----------------------------------------------------------------
    // Getter 方法
    // ----------------------------------------------------------------

    /**
     * 获取当前的 think 内容
     */
    public String getThinkContent() {
        String res = thinkContent.toString().trim();
        return StringUtils.isNotBlank(res) ? res : StringUtils.EMPTY;
    }

    /**
     * 获取 think 内容的增量部分（自上次调用后新增的部分）
     */
    public String getThinkContentIncrement() {
        int currentLength = thinkContent.length();
        if (currentLength > lastRetrievedThinkLength) {
            String increment = thinkContent.substring(lastRetrievedThinkLength, currentLength);
            lastRetrievedThinkLength = currentLength;
            return StringUtils.isNotBlank(increment) ? increment : StringUtils.EMPTY;
        }
        return StringUtils.EMPTY;
    }

    /**
     * 获取当前的回复内容
     */
    public String getReplyContent() {
        String res = replyContent.toString();
        return StringUtils.isNotBlank(res) ? res.trim() : StringUtils.EMPTY;
    }

    /**
     * 获取 reply 内容的增量部分（自上次调用后新增的部分）
     */
    public String getReplyContentIncrement() {
        int currentLength = replyContent.length();
        if (currentLength > lastRetrievedReplyLength) {
            String increment = replyContent.substring(lastRetrievedReplyLength, currentLength);
            lastRetrievedReplyLength = currentLength;
            return StringUtils.isNotBlank(increment) ? increment : StringUtils.EMPTY;
        }
        return StringUtils.EMPTY;
    }

    /**
     * 是否检测到 think 标签（标准模式）或已命中孤立 {@code </think>}（孤立标签模式）
     */
    public boolean hasThinkContent() {
        if (isOrphanEndTag) {
            return orphanPhase == OrphanPhase.REPLY;
        }
        return foundFirstThinkStart;
    }

    /**
     * think 内容是否已完成解析
     */
    public boolean isThinkComplete() {
        if (isOrphanEndTag) {
            return orphanPhase == OrphanPhase.REPLY;
        }
        return thinkTagComplete;
    }

    // ----------------------------------------------------------------
    // 重置
    // ----------------------------------------------------------------

    /**
     * 重置解析器到初始状态（构造参数保持不变）
     */
    public void reset() {
        thinkContent.setLength(0);
        replyContent.setLength(0);
        pendingBuffer.setLength(0);
        lastRetrievedThinkLength = 0;
        lastRetrievedReplyLength = 0;
        lastThinkContentLength = 0;
        lastReplyContentLength = 0;
        foundFirstThinkStart = false;
        thinkTagComplete = false;
        thinkTagDepth = 0;
        orphanPhase = OrphanPhase.PENDING;
        currentState = TagState.NORMAL;
        tagBuffer.setLength(0);
    }

}