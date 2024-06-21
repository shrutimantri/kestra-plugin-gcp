package io.kestra.plugin.gcp.cli;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.models.tasks.*;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.RunnerType;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
        title = "Execute gcloud commands."
)
@Plugin(
        examples = {
                @Example(
                        title = "Create a cluster then list them using a service account.",
                        code = {
                                "projectId: my-gcp-project",
                                "serviceAccount: \"{{ secret('gcp-sa') }}\"",
                                "commands:",
                                "  - gcloud container clusters create simple-cluster --region=europe-west3",
                                "  - gcloud container clusters list"
                        }
                ),
                @Example(
                        title = "Create a GCS bucket.",
                        code = {
                                "projectId: my-gcp-project",
                                "serviceAccount: \"{{ secret('gcp-sa') }}\"",
                                "commands:",
                                "  - gcloud storage buckets create gs://my-bucket"
                        }
                ),
                @Example(
                        title = "Output the result of a command.",
                        code = {
                                "projectId: my-gcp-project",
                                "serviceAccount: \"{{ secret('gcp-sa') }}\"",
                                "commands:",
                                "  # Outputs as a flow output for UI display",
                                "  - gcloud pubsub topics list --format=json | tr -d '\\n ' | xargs -0 -I {} echo '::{\"outputs\":{\"gcloud\":{}}}::'",
                                "",
                                "  # Outputs as a file, preferred way for large payloads",
                                "  - gcloud storage ls --json > storage.json"
                        }
                )
        }
)
public class GCloudCLI extends Task implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    private static final String DEFAULT_IMAGE = "google/cloud-sdk";

    @Schema(
        title = "The full service account JSON key to use to authenticate to gcloud."
    )
    @PluginProperty(dynamic = true)
    protected String serviceAccount;

    @Schema(
        title = "The GCP project ID to scope the commands to."
    )
    @PluginProperty(dynamic = true)
    protected String projectId;

    @Schema(
        title = "The commands to run."
    )
    @PluginProperty(dynamic = true)
    @NotNull
    @NotEmpty
    protected List<String> commands;

    @Schema(
        title = "Additional environment variables for the current process."
    )
    @PluginProperty(
            additionalProperties = String.class,
            dynamic = true
    )
    protected Map<String, String> env;

    @Schema(
        title = "Docker options when for the `DOCKER` runner.",
        defaultValue = "{image=" + DEFAULT_IMAGE + ", pullPolicy=ALWAYS}"
    )
    @PluginProperty
    @Builder.Default
    protected DockerOptions docker = DockerOptions.builder().build();

    private NamespaceFiles namespaceFiles;

    private Object inputFiles;

    private List<String> outputFiles;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {

        CommandsWrapper commands = new CommandsWrapper(runContext)
                .withWarningOnStdErr(true)
                .withRunnerType(RunnerType.DOCKER)
                .withDockerOptions(injectDefaults(getDocker()))
                .withCommands(
                        ScriptService.scriptCommands(
                                List.of("/bin/sh", "-c"),
                                null,
                                this.commands)
                );

        commands = commands.withEnv(this.getEnv(runContext))
            .withNamespaceFiles(namespaceFiles)
            .withInputFiles(inputFiles)
            .withOutputFiles(outputFiles);

        return commands.run();
    }

    private DockerOptions injectDefaults(DockerOptions original) {
        var builder = original.toBuilder();
        if (original.getImage() == null) {
            builder.image(DEFAULT_IMAGE);
        }

        return builder.build();
    }

    private Map<String, String> getEnv(RunContext runContext) throws IOException, IllegalVariableEvaluationException {
        Map<String, String> envs = new HashMap<>();

        if (serviceAccount != null) {
            Path serviceAccountPath = runContext.workingDir().createTempFile(runContext.render(this.serviceAccount).getBytes());
            envs.putAll(Map.of(
                    "GOOGLE_APPLICATION_CREDENTIALS", serviceAccountPath.toString(),
                    "CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE", serviceAccountPath.toString()
            ));
        }

        if (projectId != null) {
            envs.put("CLOUDSDK_CORE_PROJECT", runContext.render(this.projectId));
        }

        if (this.env != null) {
            envs.putAll(this.env);
        }

        return envs;
    }
}
