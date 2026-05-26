## Notes

* Create branch
* Install openspec 
  * `npm install -g @fission-ai/openspec@latest`
  * `openspec init`
* Copy specs into `specs` folder 
  * I used Claude code to generate specs and documentation from `v1-basic` codebase - lets see how well these specs do!
* Copy other docs into root
* Make sure Java 25 is available
  * `sdk install java 25.0.3-tem`
  * `sdk use java 25.0.3-tem`
  * `sdk env init`
* Update docs to use Java 25
* Update IntelliJ project to use Java 25
* The specs aren't written to OpenSpec standard so will need to be converted as they are done.
  * Should have instructed Claude to create the specs in OpenSpec format.
* propose/apply/archive F00-project-setup
  * Had trouble using Gradle since 8.12 was installed and wasn't working with Java 25. It used 21 to run Gradle.
    * It would have been better if I'd upgraded Gradle beforehand.
* Should have said to always use latest versions of libraries - it didn't always do that because of the requirements (spring boot, archunit, gradle) - should check requirements don't specify versions and just refer to latest.
* Asked Claude to convert all the specs to openspec format now
  * Can we convert all the specs to openspec format now?
    * Move existing to openspec/specs/legacy/
    * Direct doc refactor (Recommended)
* propose/apply/archive contract-first-api
* propose/apply/archive api-error-contract
* propose/apply/archive account-domain
* propose/apply/archive immutable-ledger
* Interesting that `transfer-locking` didn't have db tables at that time so implemented locking without db. Perhaps should break down differently to get db set up earlier.

...

* propose/apply/archive dev-data-seeding
  * Needed to prompt:
    * the openapi example parameters should match the seeded data
  * Manually checked and marked the 3 outstanding tasks in tasks.md as completed.


...

When implementing hateoas, the first design it used didn't use the spring hateoas framework. So after that I had to  