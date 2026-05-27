
## Introduction

Following on from the `v1-basic` version of my bank-example, I decided to take the generated code and use it as a basis for a new version, `v2-claude`. This time, I wanted to see how well I could use AI, specifically Anthropic's Claude, to help me build out a more complete application, using a specification-driven approach with OpenSpec. This blog post documents my journey, the successes, the bumps in the road, and my overall impressions.

## The Setup: Getting Started with OpenSpec

The first step was to get my project set up with OpenSpec. This involved a few key steps:

1.  **Creating a new branch** in my git repository to keep this work separate from the `v1-basic` code.
2.  **Installing OpenSpec** globally using npm: `npm install -g @fission-ai/openspec@latest`.
3.  **Initializing OpenSpec** in the project: `openspec init`.
4.  **Generating Specs**: I had previously used Claude to generate a set of specifications and documentation from the `v1-basic` codebase. I copied these into the `specs` folder.
5.  **Environment Setup**: I had to make sure I had Java 25 available, which I managed using SDKMAN. This also meant updating my IntelliJ project and some documentation to use Java 25.

A small hiccup I encountered was that the initial specs weren't in the OpenSpec format. A lesson learned here: I should have instructed Claude to generate the specs in the correct format from the start. I ended up asking Claude to convert them later.

## The Workflow: `propose/apply/archive`

With the setup complete, I started using the core OpenSpec workflow: `propose`, `apply`, and `archive`. I worked through a series of features, starting with `F00-project-setup`.

I hit a snag with Gradle, as my installed version (8.12) had issues with Java 25. It would have been smoother if I'd upgraded Gradle beforehand. Another learning point was to be less specific about library versions in my requirements, instead asking for the "latest" versions to avoid conflicts.

I then moved through a series of features, applying the same pattern:

*   `contract-first-api`
*   `api-error-contract`
*   `account-domain`
*   `immutable-ledger`

One interesting observation was during the `transfer-locking` feature. The initial implementation of locking was done without a database table, as the database wasn't set up at that point. This suggests that perhaps the feature breakdown could have been ordered differently to get the database in place earlier.

## Adding Observability and Load Testing

Once the core features were in place, I wanted to add observability to the application. A simple prompt to Claude:

```
/opsx:propose add observability to the application using prometheus and graphana.
```

This resulted in new specs for an `observability-stack` and `metrics-exposure`. To actually see some data, I also asked for a `load-generator`. This entire process was remarkably smooth, and I soon had metrics flowing into a Grafana dashboard, all with minimal effort.

## Iterating on Design: The Case of HATEOAS

A great example of the iterative power of this approach came when implementing HATEOAS. The first design Claude produced didn't use the Spring HATEOAS framework. I was able to course-correct by proposing a new spec that explicitly required the use of the Spring framework. This worked perfectly, and the improved design is now captured in the `hateoas-discovery` spec.

This demonstrates the ability to shape the application's design after the initial implementation and have those changes baked back into the main specification for future reference.

## Final Thoughts and Next Steps

After completing the main set of features, I asked Claude for suggestions on what could be done next. It produced a `Todo.md` file with a good list of potential improvements. I went ahead and got it to implement `transfer-idempotency` from that list.

Overall, I'm very impressed with the results. The generated code is well-organized, following a clean architecture pattern which is enforced with ArchUnit tests. The use of contract-first API design with OpenAPI, and the inclusion of HATEOAS with Spring, has resulted in a robust and modern API.

One implementation detail I'm still pondering is the `DbAccountLocker`. It's an interesting approach, but I'm not yet sure if it's the one I would have chosen.

This experiment has been a resounding success. Using Claude and OpenSpec, I was able to rapidly develop a well-architected application, iterate on its design, and build out a comprehensive feature set, all while maintaining a clear and executable specification.
