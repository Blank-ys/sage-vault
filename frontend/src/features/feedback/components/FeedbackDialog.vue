<template>
  <el-dialog v-model="visible" title="反馈这条回答" width="520px" @closed="reset">
    <el-form label-position="top">
      <el-form-item label="问题类别" required>
        <el-radio-group v-model="category">
          <el-radio v-for="item in FEEDBACK_CATEGORIES" :key="item.value" :value="item.value">
            {{ item.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="补充说明（选填）">
        <el-input
          v-model="comment"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="可以说明期望的答案，或指出答案哪里不对"
        />
      </el-form-item>
      <!-- 反馈会把问答内容共享给管理员，必须让用户先看清范围再自己勾选 -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="提交后，本次的问题、回答以及你填写的说明会共享给知识库管理员，用于改进知识库。"
        class="consent-notice"
      />
      <el-form-item>
        <el-checkbox v-model="consentToShare">我已阅读并同意共享上述内容</el-checkbox>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">提交反馈</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { FEEDBACK_CATEGORIES, submitFeedback } from '../api/feedback'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  qaId: { type: [Number, String], default: undefined }
})
const emit = defineEmits(['update:modelValue', 'submitted'])

const category = ref('')
const comment = ref('')
const consentToShare = ref(false)
const submitting = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
})
// 未勾选同意时提交按钮保持不可用，避免用户在不知情的情况下共享问答内容
const canSubmit = computed(() => Boolean(category.value) && consentToShare.value)

function reset() {
  category.value = ''
  comment.value = ''
  consentToShare.value = false
}

async function submit() {
  submitting.value = true
  try {
    await submitFeedback(props.qaId, {
      category: category.value,
      comment: comment.value.trim(),
      consentToShare: consentToShare.value
    })
    ElMessage.success('反馈已提交，感谢你的帮助')
    emit('submitted', props.qaId)
    visible.value = false
  } catch (error) {
    ElMessage.error(error.message || '反馈提交失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.consent-notice { margin-bottom: 12px; }
</style>
