package com.mycompany.arkitekt

import com.apollographql.apollo.api.Optional
import com.mycompany.rekuest.graphql.type.ActionKind
import com.mycompany.rekuest.graphql.type.ArgPortInput
import com.mycompany.rekuest.graphql.type.DefinitionInput
import com.mycompany.rekuest.graphql.type.PortKind
import com.mycompany.rekuest.graphql.type.ReturnPortInput

// The actions this plugin advertises to rekuest: a typed port definition per handler.
//
// Pure data, lifted out of `alogin` so the auth/composition logic there stays readable. To add
// an action, write the handler in Arkitekt.kt and register it here — it is auto-advertised on
// the next login.
fun buildFunctionRegistry(arkitekt: Arkitekt): FunctionRegistry {
    val registry = FunctionRegistry()

        registry.register_function(
            "frage",
            DefinitionInput(
                key = "frage",
                version = "0.1.0",
                name = "Upload Image",
                description =
                    Optional.present(
                        "Upload the currently active image in the viewer."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "name",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "How would you like to name the image?"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "dataset",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/arraydataset"
                                    ),
                                description =
                                    Optional.present(
                                        "The returned dataset"
                                    )
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::runX
        )

        registry.register_function(
            "show_lens",
            DefinitionInput(
                key = "show_lens",
                version = "0.1.0",
                name = "Show Lens",
                description =
                    Optional.present(
                        "Show a lens — a per-axis selection over a dataset — in the viewer. " +
                            "Only the region the lens selects is read."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "lens",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/lens"
                                    ),
                                description =
                                    Optional.present(
                                        "The lens to show"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "lens",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/lens"
                                    ),
                                description =
                                    Optional.present(
                                        "The lens that was shown"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::showLens
        )

        registry.register_function(
            "show_dataset",
            DefinitionInput(
                key = "show_dataset",
                version = "0.1.0",
                name = "Show Dataset",
                description =
                    Optional.present(
                        "Show a whole array dataset in the viewer."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "dataset",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/arraydataset"
                                    ),
                                description =
                                    Optional.present(
                                        "The dataset to show"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "dataset",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/arraydataset"
                                    ),
                                description =
                                    Optional.present(
                                        "The dataset that was shown"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::showDataset
        )

        registry.register_function(
            "annotate_lens",
            DefinitionInput(
                key = "annotate_lens",
                version = "0.1.0",
                name = "Annotate Lens",
                description =
                    Optional.present(
                        "Open a lens in the viewer and save every ROI drawn on it into a new " +
                            "annotation collection, as it is drawn. In Fiji, press `t` to bank a " +
                            "selection into the ROI Manager — that is the signal a shape is " +
                            "finished. Runs until the image window is closed or the task is " +
                            "cancelled; a dropped connection ends the session (the shapes already " +
                            "saved are kept, but a re-run starts a second collection)."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "lens",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/lens"
                                    ),
                                description =
                                    Optional.present(
                                        "The lens to open and annotate"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "collection",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/annotationcollection"
                                    ),
                                description =
                                    Optional.present(
                                        "The collection the drawn ROIs were saved into"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::annotateLens
        )

        registry.register_function(
            "run_image_to_image_macro",
            DefinitionInput(
                key = "run_image_to_image_macro",
                version = "0.1.0",
                name = "Run Image-To-Image Macro",
                description =
                    Optional.present(
                        "Run an arbitrary ImageJ macro over an image and return the result as a new image."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "dataset",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/arraydataset"
                                    ),
                                description =
                                    Optional.present(
                                        "The dataset to run the macro on"
                                    ),
                                nullable = Optional.present(false)
                            ),
                            ArgPortInput(
                                key = "macro",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "The ImageJ macro to run. It operates on the image as the current image, e.g. run(\"Gaussian Blur...\", \"sigma=2\")."
                                    ),
                                nullable = Optional.present(false)
                            ),
                            ArgPortInput(
                                key = "name",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "How would you like to name the resulting image?"
                                    ),
                                nullable = Optional.present(true)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "dataset",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/arraydataset"
                                    ),
                                description =
                                    Optional.present(
                                        "The resulting dataset after running the macro"
                                    )
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::runImageToImageMacro
        )

    return registry
}
