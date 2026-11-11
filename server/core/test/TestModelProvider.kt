package kinuvo.server

import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.server.providers.ModelProvider

/** A model provider whose source and admin halves are one object, as hosts register them. */
internal interface TestModelProvider : ModelProvider, ModelSource
